import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { CreateBucketCommand, GetObjectCommand, PutObjectCommand, S3Client } from "@aws-sdk/client-s3";
import express from "express";
import multer from "multer";
import pg from "pg";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.resolve(__dirname, "../public");
const port = Number(process.env.PORT || 8090);
const adminPassword = process.env.NIBBL_ADMIN_PASSWORD || "";
const databaseUrl = process.env.DATABASE_URL || "postgres://nibbl:nibbl@postgres:5432/nibbl";
const objectMode = (process.env.OBJECT_STORAGE || "local").toLowerCase();
const localObjectDir = process.env.OBJECT_LOCAL_DIR || "/data/objects";
const s3Bucket = process.env.S3_BUCKET || "nibbl";
const s3PublicBaseUrl = (process.env.S3_PUBLIC_BASE_URL || "").replace(/\/+$/, "");

const pool = new pg.Pool({ connectionString: databaseUrl });
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 12 * 1024 * 1024 } });
let dbReady = false;
let lastBootstrapError = "";
const rateBuckets = new Map();
const s3 = objectMode === "s3"
  ? new S3Client({
      region: process.env.S3_REGION || "us-east-1",
      endpoint: process.env.S3_ENDPOINT || undefined,
      forcePathStyle: (process.env.S3_FORCE_PATH_STYLE || "true") === "true",
      credentials: process.env.S3_ACCESS_KEY_ID
        ? {
            accessKeyId: process.env.S3_ACCESS_KEY_ID,
            secretAccessKey: process.env.S3_SECRET_ACCESS_KEY || "",
          }
        : undefined,
    })
  : null;

const app = express();
app.disable("x-powered-by");
app.use(securityHeaders);
app.use(rateLimit);
app.use(express.json({ limit: "2mb" }));
app.use(express.urlencoded({ extended: false }));
app.use(express.static(publicDir, { extensions: ["html"], index: false }));

app.get("/api/health", (_req, res) => res.json({
  status: dbReady ? "healthy" : "starting",
  backend: "postgres",
  database: dbReady ? "ready" : "waiting",
  error: dbReady ? undefined : lastBootstrapError || undefined,
}));

app.post("/api/nibbl/devices/register", async (req, res, next) => {
  try {
    const ownerId = text(req.body.ownerId, 64) || crypto.randomUUID();
    const ownerName = text(req.body.ownerName, 48) || "Me";
    const ownerTag = friendTag(req.body.ownerTag);
    const token = crypto.randomBytes(32).toString("base64url");
    const tokenHash = tokenHashFor(token);
    await pool.query(
      `insert into devices (owner_id, owner_name, owner_tag, token_hash)
       values ($1,$2,$3,$4)
       on conflict (owner_id) do update set
        owner_name = excluded.owner_name,
        owner_tag = excluded.owner_tag,
        token_hash = excluded.token_hash,
        updated_at = now()`,
      [ownerId, ownerName, ownerTag, tokenHash],
    );
    res.status(201).json({ ownerId, apiToken: token });
  } catch (error) {
    next(error);
  }
});

app.post("/api/nibbl/ingest", requireDeviceAuth, upload.fields([
  { name: "image", maxCount: 1 },
  { name: "cutout", maxCount: 1 },
  { name: "original", maxCount: 1 },
]), async (req, res, next) => {
  try {
    const body = parseBody(req.body);
    const cutoutFile = firstFile(req.files, "cutout") || firstFile(req.files, "image");
    const originalFile = firstFile(req.files, "original");
    const imageObject = cutoutFile ? await putObject(cutoutFile, "cutouts") : null;
    const originalObject = originalFile ? await putObject(originalFile, "originals") : null;
    const timestamp = Number(body.timestamp || Date.now());
    const logDate = new Date(timestamp).toISOString().slice(0, 10);
    const friendNames = arrayValue(body.friendNames);

    const result = await pool.query(
      `insert into logs (
        owner_id, owner_name, owner_tag, timestamp_ms, log_date, title, category, caffeine_mg,
        cafe, location_name, latitude, longitude, friend_names, image_key, original_image_key, source
      ) values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13::jsonb,$14,$15,$16)
      returning id`,
      [
        req.device.owner_id,
        text(body.ownerName, 48) || req.device.owner_name,
        friendTag(body.ownerTag) || req.device.owner_tag,
        timestamp,
        logDate,
        text(body.title, 80),
        text(body.category || "drink", 32),
        nullableInt(body.caffeineMg),
        text(body.cafe, 120),
        text(body.locationName, 160),
        nullableFloat(body.latitude),
        nullableFloat(body.longitude),
        JSON.stringify(friendNames),
        imageObject?.key || null,
        originalObject?.key || null,
        "android",
      ],
    );

    res.status(201).json({
      id: result.rows[0].id,
      imageUrl: imageObject?.url || null,
      originalImageUrl: originalObject?.url || null,
    });
  } catch (error) {
    next(error);
  }
});

app.get("/api/nibbl/stats", async (_req, res, next) => {
  try {
    const stats = await readStats();
    res.json({ ...stats, status: "healthy" });
  } catch (error) {
    res.json({ entries: 0, cafes: 0, friends: 0, categories: 0, status: "starting" });
  }
});

app.get("/api/nibbl/friends/available", async (req, res, next) => {
  try {
    const tag = friendTag(req.query.tag);
    if (!tag || tag.length < 3) return res.json({ tag, available: false, reason: "too_short" });
    const { rows } = await pool.query("select 1 from logs where owner_tag = $1 limit 1", [tag]);
    res.json({ tag, available: rows.length === 0 });
  } catch (error) {
    next(error);
  }
});

app.post("/api/nibbl/profile", requireDeviceAuth, async (req, res, next) => {
  try {
    const ownerId = req.device.owner_id;
    if (!ownerId) return res.status(400).json({ error: "ownerId_required" });
    const ownerName = text(req.body.ownerName, 48);
    const ownerTag = friendTag(req.body.ownerTag);
    const [logResult] = await Promise.all([
      pool.query("update logs set owner_name = $1, owner_tag = $2 where owner_id = $3", [ownerName, ownerTag, ownerId]),
      pool.query("update devices set owner_name = $1, owner_tag = $2, updated_at = now() where owner_id = $3", [ownerName, ownerTag, ownerId]),
    ]);
    res.json({ updated: logResult.rowCount });
  } catch (error) {
    next(error);
  }
});

app.post("/api/nibbl/shares/day", requireDeviceAuth, async (req, res, next) => {
  try {
    const date = dateFromSlug(text(req.body.date, 32));
    const token = crypto.randomBytes(18).toString("base64url");
    await pool.query(
      `insert into day_shares (token, owner_id, owner_name, owner_tag, log_date)
       values ($1,$2,$3,$4,$5)`,
      [token, req.device.owner_id, req.device.owner_name, req.device.owner_tag, date],
    );
    res.status(201).json({
      token,
      date,
      url: `/?s=${encodeURIComponent(token)}`,
    });
  } catch (error) {
    next(error);
  }
});

app.get("/api/admin/stats", requireAdmin, async (_req, res, next) => {
  try {
    res.json(await readStats(true));
  } catch (error) {
    next(error);
  }
});

app.get("/api/admin/logs", requireAdmin, async (req, res, next) => {
  try {
    const limit = Math.min(Number(req.query.limit || 80), 250);
    const { rows } = await pool.query(
      `select id, owner_name, owner_tag, timestamp_ms, log_date, title, category, caffeine_mg,
        cafe, location_name, friend_names, image_key, created_at
       from logs order by created_at desc limit $1`,
      [limit],
    );
    res.json(rows.map(rowToLog));
  } catch (error) {
    next(error);
  }
});

app.get("/api/nibbl/day/:date", async (req, res, next) => {
  try {
    res.json(await dayPayload(req.params.date));
  } catch (error) {
    next(error);
  }
});

app.get("/objects/*", async (req, res, next) => {
  try {
    await sendObject(req.params[0], res);
  } catch (error) {
    next(error);
  }
});

app.get(["/i/:slug", "/share/:slug"], async (req, res, next) => {
  try {
    const date = dateFromSlug(req.params.slug);
    res.send(renderSharePage(await safeDayPayload(date), date));
  } catch (error) {
    next(error);
  }
});

app.get("/s/:token", async (req, res, next) => {
  try {
    const share = await shareByToken(req.params.token);
    res.send(renderSharePage(await safeDayPayload(share.log_date), share.log_date, share));
  } catch (error) {
    next(error);
  }
});

app.get("/", async (req, res, next) => {
  try {
    if (req.query.s) {
      const share = await shareByToken(String(req.query.s));
      return res.send(renderSharePage(await safeDayPayload(share.log_date), share.log_date, share));
    }
    if (req.query.i || req.query.invite || req.query.token) {
      const date = dateFromSlug(String(req.query.i || req.query.invite || req.query.token));
      return res.send(renderSharePage(await safeDayPayload(date), date));
    }
    res.sendFile(path.join(publicDir, "index.html"));
  } catch (error) {
    next(error);
  }
});

app.use((error, _req, res, _next) => {
  console.error(error);
  res.status(error.status || 500).json({ error: error.message || "server_error" });
});

app.listen(port, () => {
  console.log(`Nibbl backend listening on ${port}`);
});
bootstrapWithRetry();

async function bootstrapWithRetry() {
  while (!dbReady) {
    try {
      await bootstrap();
      dbReady = true;
      lastBootstrapError = "";
      console.log("Nibbl backend storage is ready");
    } catch (error) {
      lastBootstrapError = error.message || "bootstrap_failed";
      console.error("Nibbl backend storage is not ready yet:", lastBootstrapError);
      await sleep(5_000);
    }
  }
}

async function bootstrap() {
  await fs.mkdir(localObjectDir, { recursive: true });
  if (s3) {
    await s3.send(new CreateBucketCommand({ Bucket: s3Bucket })).catch((error) => {
      if (!["BucketAlreadyOwnedByYou", "BucketAlreadyExists"].includes(error.name)) throw error;
    });
  }
  await pool.query(`
    create extension if not exists pgcrypto;
    create table if not exists logs (
      id uuid primary key default gen_random_uuid(),
      owner_id text not null default '',
      owner_name text not null default '',
      owner_tag text not null default '',
      timestamp_ms bigint not null,
      log_date date not null,
      title text not null default '',
      category text not null default 'drink',
      caffeine_mg integer,
      cafe text not null default '',
      location_name text not null default '',
      latitude double precision,
      longitude double precision,
      friend_names jsonb not null default '[]'::jsonb,
      image_key text,
      original_image_key text,
      source text not null default 'android',
      created_at timestamptz not null default now(),
      updated_at timestamptz not null default now()
    );
    create index if not exists logs_date_idx on logs(log_date);
    create index if not exists logs_owner_tag_idx on logs(owner_tag);
    create index if not exists logs_created_at_idx on logs(created_at desc);
    create table if not exists share_visits (
      id uuid primary key default gen_random_uuid(),
      slug text not null,
      visited_at timestamptz not null default now()
    );
    create table if not exists devices (
      owner_id text primary key,
      owner_name text not null default '',
      owner_tag text not null default '',
      token_hash text not null unique,
      created_at timestamptz not null default now(),
      updated_at timestamptz not null default now()
    );
    create table if not exists day_shares (
      token text primary key,
      owner_id text not null,
      owner_name text not null default '',
      owner_tag text not null default '',
      log_date date not null,
      created_at timestamptz not null default now()
    );
    create index if not exists day_shares_date_idx on day_shares(log_date);
  `);
}

async function safeDayPayload(date) {
  try {
    return await dayPayload(date);
  } catch {
    return { date, logs: [] };
  }
}

async function readStats(includeRecent = false) {
  const [{ rows: counts }, { rows: categories }, { rows: cafes }, { rows: friends }] = await Promise.all([
    pool.query("select count(*)::int as entries from logs"),
    pool.query("select count(distinct category)::int as categories from logs where category <> ''"),
    pool.query("select count(distinct lower(cafe))::int as cafes from logs where cafe <> ''"),
    pool.query("select count(distinct owner_tag)::int as friends from logs where owner_tag <> ''"),
  ]);
  const stats = {
    entries: counts[0]?.entries || 0,
    cafes: cafes[0]?.cafes || 0,
    friends: friends[0]?.friends || 0,
    categories: categories[0]?.categories || 0,
  };
  if (includeRecent) {
    const { rows } = await pool.query("select log_date, count(*)::int as total from logs group by log_date order by log_date desc limit 14");
    stats.recentDays = rows;
  }
  return stats;
}

async function dayPayload(date) {
  const { rows } = await pool.query(
    `select id, owner_name, owner_tag, timestamp_ms, log_date, title, category, caffeine_mg,
      cafe, location_name, friend_names, image_key, created_at
     from logs where log_date = $1 order by timestamp_ms asc`,
    [date],
  );
  return { date, logs: rows.map(rowToLog) };
}

async function shareByToken(token) {
  const cleanToken = String(token || "").trim();
  if (!/^[a-zA-Z0-9_-]{16,80}$/.test(cleanToken)) {
    const error = new Error("Share not found");
    error.status = 404;
    throw error;
  }
  const { rows } = await pool.query("select token, owner_id, owner_name, owner_tag, log_date from day_shares where token = $1", [cleanToken]);
  if (!rows.length) {
    const error = new Error("Share not found");
    error.status = 404;
    throw error;
  }
  return {
    ...rows[0],
    log_date: normalizeDateValue(rows[0].log_date),
  };
}

function rowToLog(row) {
  return {
    id: row.id,
    ownerName: row.owner_name,
    ownerTag: row.owner_tag,
    timestamp: Number(row.timestamp_ms),
    date: row.log_date,
    title: row.title,
    category: row.category,
    caffeineMg: row.caffeine_mg,
    cafe: row.cafe,
    locationName: row.location_name,
    friendNames: Array.isArray(row.friend_names) ? row.friend_names : [],
    imageUrl: row.image_key ? objectUrl(row.image_key) : null,
    createdAt: row.created_at,
  };
}

async function putObject(file, prefix) {
  const ext = extensionFor(file);
  const key = `${prefix}/${new Date().toISOString().slice(0, 10)}/${crypto.randomUUID()}${ext}`;
  if (s3) {
    await s3.send(new PutObjectCommand({
      Bucket: s3Bucket,
      Key: key,
      Body: file.buffer,
      ContentType: file.mimetype || "application/octet-stream",
    }));
  } else {
    const target = path.join(localObjectDir, key);
    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.writeFile(target, file.buffer);
  }
  return { key, url: objectUrl(key) };
}

async function sendObject(key, res) {
  if (!/^[a-zA-Z0-9/_-]+\.(png|jpe?g|webp)$/i.test(key)) {
    res.status(404).end();
    return;
  }
  if (s3) {
    const object = await s3.send(new GetObjectCommand({ Bucket: s3Bucket, Key: key }));
    res.type(contentTypeFor(key));
    object.Body.pipe(res);
    return;
  }
  res.type(contentTypeFor(key));
  res.sendFile(path.join(localObjectDir, key));
}

function objectUrl(key) {
  return s3PublicBaseUrl ? `${s3PublicBaseUrl}/${key}` : `/objects/${key}`;
}

function renderSharePage(day, date, share = null) {
  const cards = day.logs.map((log) => `
    <article class="card">
      ${log.imageUrl ? `<img src="${escapeHtml(log.imageUrl)}" alt="">` : `<div class="placeholder">Nibbl</div>`}
      <div><strong>${escapeHtml(log.title || "Food + drink")}</strong><span>${escapeHtml(log.cafe || log.locationName || "Saved moment")}</span></div>
    </article>
  `).join("");
  const owner = share?.owner_name || share?.owner_tag || "";
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Nibbl ${escapeHtml(date)}</title><link rel="stylesheet" href="/styles.css"></head><body><main><section class="hero compact"><div class="mark">N</div><h1>${escapeHtml(date)}</h1><p>${owner ? `${escapeHtml(owner)} shared this day. ` : ""}${day.logs.length ? `${day.logs.length} saved food and drink moments.` : "No public logs have synced for this day yet."}</p></section><section class="share-grid">${cards}</section></main></body></html>`;
}

async function requireDeviceAuth(req, res, next) {
  try {
    const token = bearerToken(req);
    if (!token) return res.status(401).json({ error: "missing_device_token" });
    const { rows } = await pool.query(
      "select owner_id, owner_name, owner_tag from devices where token_hash = $1",
      [tokenHashFor(token)],
    );
    if (!rows.length) return res.status(403).json({ error: "invalid_device_token" });
    req.device = rows[0];
    next();
  } catch (error) {
    next(error);
  }
}

function requireAdmin(req, res, next) {
  if (!adminPassword) return res.status(503).json({ error: "admin_password_not_set" });
  const auth = req.header("authorization") || "";
  const expected = `Basic ${Buffer.from(`admin:${adminPassword}`).toString("base64")}`;
  if (auth !== expected) {
    res.setHeader("WWW-Authenticate", 'Basic realm="Nibbl admin"');
    return res.status(401).end();
  }
  next();
}

function securityHeaders(_req, res, next) {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
  res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  res.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'");
  next();
}

function rateLimit(req, res, next) {
  const key = `${req.ip}:${req.path}`;
  const now = Date.now();
  const windowMs = req.path.includes("/devices/register") || req.path.includes("/ingest") ? 60_000 : 30_000;
  const limit = req.path.includes("/devices/register") ? 12 : req.path.includes("/ingest") ? 30 : 180;
  const bucket = rateBuckets.get(key) || { count: 0, resetAt: now + windowMs };
  if (bucket.resetAt <= now) {
    bucket.count = 0;
    bucket.resetAt = now + windowMs;
  }
  bucket.count += 1;
  rateBuckets.set(key, bucket);
  if (bucket.count > limit) return res.status(429).json({ error: "rate_limited" });
  next();
}

function bearerToken(req) {
  const auth = req.header("authorization") || "";
  return auth.toLowerCase().startsWith("bearer ") ? auth.slice(7).trim() : "";
}

function tokenHashFor(token) {
  return crypto.createHash("sha256").update(String(token)).digest("hex");
}

function parseBody(body) {
  if (typeof body.payload === "string") {
    try {
      return JSON.parse(body.payload);
    } catch {
      return body;
    }
  }
  return body || {};
}

function firstFile(files, name) {
  return files?.[name]?.[0] || null;
}

function arrayValue(value) {
  if (Array.isArray(value)) return value.map((item) => text(item, 48)).filter(Boolean);
  if (typeof value === "string") {
    try {
      const parsed = JSON.parse(value);
      if (Array.isArray(parsed)) return parsed.map((item) => text(item, 48)).filter(Boolean);
    } catch {}
    return value.split(",").map((item) => text(item, 48)).filter(Boolean);
  }
  return [];
}

function nullableInt(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.round(number) : null;
}

function nullableFloat(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function text(value, max) {
  return String(value ?? "").trim().slice(0, max);
}

function friendTag(value) {
  return text(value, 40).toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 10);
}

function dateFromSlug(slug) {
  const compact = String(slug || "").split("-")[0];
  if (/^\d{8}$/.test(compact)) return `${compact.slice(0, 4)}-${compact.slice(4, 6)}-${compact.slice(6, 8)}`;
  if (/^\d{4}-\d{2}-\d{2}$/.test(compact)) return compact;
  return new Date().toISOString().slice(0, 10);
}

function normalizeDateValue(value) {
  if (value instanceof Date) return value.toISOString().slice(0, 10);
  return String(value || "").slice(0, 10);
}

function extensionFor(file) {
  const byMime = { "image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp" };
  return byMime[file.mimetype] || path.extname(file.originalname || "").toLowerCase().match(/^\.(png|jpe?g|webp)$/)?.[0] || ".jpg";
}

function contentTypeFor(key) {
  if (key.endsWith(".png")) return "image/png";
  if (key.endsWith(".webp")) return "image/webp";
  return "image/jpeg";
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  }[char]));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
