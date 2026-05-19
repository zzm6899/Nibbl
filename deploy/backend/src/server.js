import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { CreateBucketCommand, GetObjectCommand, ListObjectsV2Command, PutObjectCommand, S3Client } from "@aws-sdk/client-s3";
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
const shareExpiryDays = Math.max(1, Math.min(Number(process.env.NIBBL_SHARE_EXPIRY_DAYS || 30), 365));
const storageCapacityBytes = Math.max(0, Number(process.env.NIBBL_STORAGE_CAPACITY_BYTES || 0));

const pool = new pg.Pool({ connectionString: databaseUrl });
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 12 * 1024 * 1024 } });
let dbReady = false;
let lastBootstrapError = "";
let objectReady = objectMode !== "s3";
let lastObjectError = "";
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

app.get(["/admin", "/admin.html"], requireAdmin, (_req, res) => {
  res.sendFile(path.join(publicDir, "admin.html"));
});

app.use(express.static(publicDir, { extensions: ["html"], index: false }));

app.get("/api/health", (_req, res) => res.json({
  status: dbReady && objectReady ? "healthy" : dbReady ? "degraded" : "starting",
  backend: "postgres",
  database: dbReady ? "ready" : "waiting",
  objectStorage: objectReady ? "ready" : "waiting",
  error: process.env.NIBBL_HEALTH_DETAILS === "true" && !dbReady ? lastBootstrapError || undefined : undefined,
  objectError: process.env.NIBBL_HEALTH_DETAILS === "true" && !objectReady ? lastObjectError || undefined : undefined,
}));

app.post("/api/nibbl/waitlist", async (req, res, next) => {
  try {
    const email = emailAddress(req.body.email);
    if (!email) return res.status(400).json({ error: "valid_email_required" });
    const source = text(req.body.source || "landing", 40) || "landing";
    await pool.query(
      `insert into waitlist_signups (email, source)
       values ($1,$2)
       on conflict (email) do update set
        source = excluded.source,
        updated_at = now()`,
      [email, source],
    );
    res.status(201).json({ ok: true });
  } catch (error) {
    next(error);
  }
});

app.post("/api/nibbl/devices/register", async (req, res, next) => {
  try {
    const requestedOwnerId = text(req.body.ownerId, 64);
    const ownerId = requestedOwnerId || crypto.randomUUID();
    const ownerName = text(req.body.ownerName, 48) || "Me";
    const ownerTag = friendTag(req.body.ownerTag);
    if (requestedOwnerId) {
      const { rows } = await pool.query("select 1 from devices where owner_id = $1 limit 1", [requestedOwnerId]);
      if (rows.length) return res.status(409).json({ error: "owner_exists" });
    }
    await assertFriendTagAvailable(ownerTag, ownerId);
    const token = crypto.randomBytes(32).toString("base64url");
    const tokenHash = tokenHashFor(token);
    await pool.query(
      `insert into devices (owner_id, owner_name, owner_tag, token_hash)
       values ($1,$2,$3,$4)`,
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
    const logDate = body.logDate ? dateFromSlug(text(body.logDate, 32)) : new Date(timestamp).toISOString().slice(0, 10);
    const friendNames = arrayValue(body.friendNames);
    const clientLogId = text(body.clientLogId, 80);

    const result = await pool.query(
      `insert into logs (
        owner_id, client_log_id, owner_name, owner_tag, timestamp_ms, log_date, title, category, caffeine_mg,
        cafe, location_name, latitude, longitude, friend_names, sticker, image_key, original_image_key, source
      ) values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14::jsonb,$15,$16,$17,$18)
      on conflict (owner_id, client_log_id) where client_log_id <> ''
      do update set
        owner_name = excluded.owner_name,
        owner_tag = excluded.owner_tag,
        timestamp_ms = excluded.timestamp_ms,
        log_date = excluded.log_date,
        title = excluded.title,
        category = excluded.category,
        caffeine_mg = excluded.caffeine_mg,
        cafe = excluded.cafe,
        location_name = excluded.location_name,
        latitude = excluded.latitude,
        longitude = excluded.longitude,
        friend_names = excluded.friend_names,
        sticker = excluded.sticker,
        image_key = coalesce(excluded.image_key, logs.image_key),
        original_image_key = coalesce(excluded.original_image_key, logs.original_image_key),
        updated_at = now()
      returning id`,
      [
        req.device.owner_id,
        clientLogId,
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
        text(body.sticker, 16),
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
    const { rows } = await pool.query(
      `select 1 from devices where owner_tag = $1
       union all
       select 1 from logs where owner_tag = $1
       limit 1`,
      [tag],
    );
    res.json({ tag, available: rows.length === 0 });
  } catch (error) {
    next(error);
  }
});

app.get("/api/nibbl/friends/resolve", async (req, res, next) => {
  try {
    const tag = friendTag(req.query.tag);
    if (!tag || tag.length < 3) return res.status(400).json({ error: "tag_required" });
    const { rows } = await pool.query(
      `select owner_name, owner_tag, avatar_key from devices where owner_tag = $1
       union all
       select owner_name, owner_tag, null as avatar_key from logs where owner_tag = $1
       limit 1`,
      [tag],
    );
    if (!rows.length) return res.status(404).json({ error: "friend_not_found" });
    res.json({
      displayName: rows[0].owner_name || rows[0].owner_tag,
      tag: rows[0].owner_tag,
      avatarUrl: rows[0].avatar_key ? objectUrl(rows[0].avatar_key) : null,
    });
  } catch (error) {
    next(error);
  }
});

app.post("/api/nibbl/profile", requireDeviceAuth, upload.fields([{ name: "avatar", maxCount: 1 }]), async (req, res, next) => {
  try {
    const body = parseBody(req.body);
    const ownerId = req.device.owner_id;
    if (!ownerId) return res.status(400).json({ error: "ownerId_required" });
    const ownerName = text(body.ownerName, 48);
    const ownerTag = friendTag(body.ownerTag);
    await assertFriendTagAvailable(ownerTag, ownerId);
    const avatarFile = firstFile(req.files, "avatar");
    const avatarObject = avatarFile ? await putObject(avatarFile, "avatars") : null;
    const [logResult] = await Promise.all([
      pool.query("update logs set owner_name = $1, owner_tag = $2 where owner_id = $3", [ownerName, ownerTag, ownerId]),
      pool.query(
        `update devices
         set owner_name = $1, owner_tag = $2, avatar_key = coalesce($3, avatar_key), updated_at = now()
         where owner_id = $4`,
        [ownerName, ownerTag, avatarObject?.key || null, ownerId],
      ),
    ]);
    res.json({ updated: logResult.rowCount, avatarUrl: avatarObject?.url || null });
  } catch (error) {
    next(error);
  }
});

app.post("/api/nibbl/shares/day", requireDeviceAuth, async (req, res, next) => {
  try {
    const date = dateFromSlug(text(req.body.date, 32));
    const token = crypto.randomBytes(18).toString("base64url");
    await pool.query(
      `insert into day_shares (token, owner_id, owner_name, owner_tag, log_date, expires_at)
       values ($1,$2,$3,$4,$5, now() + ($6::text || ' days')::interval)`,
      [token, req.device.owner_id, req.device.owner_name, req.device.owner_tag, date, shareExpiryDays],
    );
    res.status(201).json({
      token,
      date,
      expiresAt: new Date(Date.now() + shareExpiryDays * 24 * 60 * 60 * 1000).toISOString(),
      url: `/?s=${encodeURIComponent(token)}`,
    });
  } catch (error) {
    next(error);
  }
});

app.get("/api/admin/stats", requireAdmin, async (_req, res, next) => {
  try {
    await ensureSchemaReady();
    res.json(await readStats(true));
  } catch (error) {
    next(error);
  }
});

app.get("/api/admin/logs", requireAdmin, async (req, res, next) => {
  try {
    await ensureSchemaReady();
    const limit = Math.min(Number(req.query.limit || 80), 250);
    const rows = await adminLogRows(limit);
    res.json(rows.map(rowToLog));
  } catch (error) {
    next(error);
  }
});

app.get("/api/nibbl/logs", requireDeviceAuth, async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      `select id, owner_name, owner_tag, timestamp_ms, log_date, title, category, caffeine_mg,
        cafe, location_name, friend_names, sticker, image_key, original_image_key, created_at
       from logs where owner_id = $1 order by timestamp_ms asc`,
      [req.device.owner_id],
    );
    res.json({ logs: rows.map(rowToLog) });
  } catch (error) {
    next(error);
  }
});

app.get("/api/nibbl/day/:date", async (req, res, next) => {
  try {
    if (process.env.NIBBL_PUBLIC_DAY_API !== "true") {
      return res.status(404).json({ error: "not_found" });
    }
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
    res.redirect(302, "/");
  } catch (error) {
    next(error);
  }
});

app.get("/s/:token", async (req, res, next) => {
  try {
    const share = await shareByToken(req.params.token);
    recordShareVisit(share.token);
    res.send(renderSharePage(await safeSharedDayPayload(share), share.log_date, share));
  } catch (error) {
    next(error);
  }
});

app.get("/", async (req, res, next) => {
  try {
    if (req.query.s) {
      const share = await shareByToken(String(req.query.s));
      recordShareVisit(share.token);
      return res.send(renderSharePage(await safeSharedDayPayload(share), share.log_date, share));
    }
    if (req.query.i || req.query.invite || req.query.token) {
      return res.sendFile(path.join(publicDir, "index.html"));
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

async function ensureSchemaReady() {
  await bootstrap();
  dbReady = true;
  lastBootstrapError = "";
}

async function bootstrap() {
  await fs.mkdir(localObjectDir, { recursive: true });
  await pool.query(`
    create extension if not exists pgcrypto;
    create table if not exists logs (
      id uuid primary key default gen_random_uuid(),
      owner_id text not null default '',
      client_log_id text not null default '',
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
      sticker text not null default '',
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
      avatar_key text,
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
      created_at timestamptz not null default now(),
      expires_at timestamptz
    );
    create index if not exists day_shares_date_idx on day_shares(log_date);
    create index if not exists day_shares_expires_at_idx on day_shares(expires_at);
    create table if not exists waitlist_signups (
      email text primary key,
      source text not null default 'landing',
      created_at timestamptz not null default now(),
      updated_at timestamptz not null default now()
    );
    create index if not exists waitlist_signups_created_at_idx on waitlist_signups(created_at desc);
  `);
  await pool.query("alter table logs add column if not exists client_log_id text not null default ''");
  await pool.query("alter table logs add column if not exists sticker text not null default ''");
  await pool.query("alter table logs add column if not exists original_image_key text");
  await pool.query("create unique index if not exists logs_owner_client_log_idx on logs(owner_id, client_log_id) where client_log_id <> ''");
  await pool.query("alter table devices add column if not exists avatar_key text");
  await pool.query("alter table day_shares add column if not exists expires_at timestamptz");
  await pool.query("update day_shares set expires_at = created_at + ($1::text || ' days')::interval where expires_at is null", [shareExpiryDays]);
  await ensureObjectStorage();
}

async function ensureObjectStorage() {
  if (!s3) {
    objectReady = true;
    lastObjectError = "";
    return;
  }
  try {
    await s3.send(new CreateBucketCommand({ Bucket: s3Bucket })).catch((error) => {
      if (!["BucketAlreadyOwnedByYou", "BucketAlreadyExists"].includes(error.name)) throw error;
    });
    objectReady = true;
    lastObjectError = "";
  } catch (error) {
    objectReady = false;
    lastObjectError = error.message || "object_storage_failed";
    console.error("Nibbl object storage is not ready:", lastObjectError);
  }
}

async function safeDayPayload(date) {
  try {
    return await dayPayload(date);
  } catch {
    return { date, logs: [] };
  }
}

async function safeSharedDayPayload(share) {
  try {
    return await dayPayload(share.log_date, share.owner_id);
  } catch {
    return { date: share.log_date, logs: [] };
  }
}

async function readStats(includeRecent = false) {
  const [
    { rows: counts },
    { rows: categories },
    { rows: cafes },
    { rows: friends },
    { rows: devices },
    { rows: shares },
    { rows: visits },
    { rows: imageLogs },
    { rows: avatarDevices },
    { rows: recentUploads },
    { rows: waitlist },
    storage,
  ] = await Promise.all([
    pool.query(`
      select
        count(*)::int as entries,
        count(*) filter (where created_at >= now() - interval '24 hours')::int as entries_today,
        count(*) filter (where created_at >= now() - interval '7 days')::int as entries_week,
        count(distinct owner_id)::int as active_loggers,
        coalesce(sum(case when image_key is not null then 1 else 0 end), 0)::int as image_entries,
        coalesce(sum(case when original_image_key is not null then 1 else 0 end), 0)::int as original_entries,
        coalesce(sum(coalesce(caffeine_mg, 0)), 0)::int as caffeine_total
      from logs
    `),
    pool.query("select count(distinct category)::int as categories from logs where category <> ''"),
    pool.query("select count(distinct lower(cafe))::int as cafes from logs where cafe <> ''"),
    pool.query(
      `select count(distinct owner_tag)::int as friends from (
        select owner_tag from devices where owner_tag <> ''
        union all
        select owner_tag from logs where owner_tag <> ''
      ) tags`,
    ),
    pool.query(`
      select
        count(*)::int as devices,
        count(*) filter (where created_at >= now() - interval '7 days')::int as new_devices_week
      from devices
    `),
    pool.query(`
      select
        count(*)::int as shares,
        count(*) filter (where created_at >= now() - interval '7 days')::int as shares_week
      from day_shares
    `),
    pool.query("select count(*)::int as share_visits from share_visits"),
    pool.query("select count(*)::int as image_logs from logs where image_key is not null"),
    pool.query("select count(*)::int as avatar_devices from devices where avatar_key is not null"),
    pool.query("select max(created_at) as last_upload_at from logs"),
    pool.query("select count(*)::int as waitlist_signups from waitlist_signups"),
    storageStats(),
  ]);
  const countRow = counts[0] || {};
  const stats = {
    entries: countRow.entries || 0,
    entriesToday: countRow.entries_today || 0,
    entriesWeek: countRow.entries_week || 0,
    activeLoggers: countRow.active_loggers || 0,
    imageEntries: countRow.image_entries || imageLogs[0]?.image_logs || 0,
    originalEntries: countRow.original_entries || 0,
    caffeineTotal: countRow.caffeine_total || 0,
    cafes: cafes[0]?.cafes || 0,
    friends: friends[0]?.friends || 0,
    categories: categories[0]?.categories || 0,
    devices: devices[0]?.devices || 0,
    newDevicesWeek: devices[0]?.new_devices_week || 0,
    shares: shares[0]?.shares || 0,
    sharesWeek: shares[0]?.shares_week || 0,
    shareVisits: visits[0]?.share_visits || 0,
    avatarDevices: avatarDevices[0]?.avatar_devices || 0,
    waitlistSignups: waitlist[0]?.waitlist_signups || 0,
    lastUploadAt: recentUploads[0]?.last_upload_at || null,
    storage,
    health: {
      database: dbReady ? "ready" : "waiting",
      objectStorage: objectReady ? "ready" : "waiting",
      objectMode,
      publicBaseUrl: s3PublicBaseUrl || "local",
    },
  };
  if (includeRecent) {
    const [
      recentDays,
      topCategories,
      topCafes,
      topFriends,
      sourceBreakdown,
      recentShares,
      recentWaitlist,
    ] = await Promise.all([
      safeRows("admin recent days", "select log_date, count(*)::int as total from logs group by log_date order by log_date desc limit 14"),
      safeRows("admin top categories", `
        select category as label, count(*)::int as total
        from logs
        where category <> ''
        group by category
        order by total desc, category asc
        limit 8
      `),
      safeRows("admin top cafes", `
        select cafe as label, count(*)::int as total
        from logs
        where cafe <> ''
        group by cafe
        order by total desc, cafe asc
        limit 8
      `),
      safeRows("admin top friends", `
        select coalesce(nullif(owner_name, ''), owner_tag, 'Unknown') as label, count(*)::int as total
        from logs
        group by label
        order by total desc, label asc
        limit 8
      `),
      safeRows("admin source breakdown", `
        select source as label, count(*)::int as total
        from logs
        group by source
        order by total desc, source asc
      `),
      safeRows("admin recent shares", `
        select token, owner_name, owner_tag, log_date, created_at, expires_at
        from day_shares
        order by created_at desc
        limit 8
      `),
      safeRows("admin recent waitlist", `
        select email as label, source, created_at
        from waitlist_signups
        order by created_at desc
        limit 8
      `),
    ]);
    stats.recentDays = recentDays.map((row) => ({ date: normalizeDateValue(row.log_date), total: row.total }));
    stats.topCategories = topCategories;
    stats.topCafes = topCafes;
    stats.topFriends = topFriends;
    stats.sourceBreakdown = sourceBreakdown;
    stats.recentShares = recentShares.map((row) => ({
      token: row.token,
      ownerName: row.owner_name,
      ownerTag: row.owner_tag,
      date: normalizeDateValue(row.log_date),
      createdAt: row.created_at,
      expiresAt: row.expires_at,
      url: `/?s=${encodeURIComponent(row.token)}`,
    }));
    stats.recentWaitlist = recentWaitlist.map((row) => ({
      label: row.label,
      source: row.source,
      createdAt: row.created_at,
    }));
  }
  return stats;
}

async function safeRows(label, sql, params = []) {
  try {
    const { rows } = await pool.query(sql, params);
    return rows;
  } catch (error) {
    console.error(`${label} failed:`, error.message || "query_failed");
    return [];
  }
}

async function adminLogRows(limit) {
  try {
    const { rows } = await pool.query(
      `select id, owner_name, owner_tag, timestamp_ms, log_date, title, category, caffeine_mg,
        cafe, location_name, friend_names, sticker, image_key, original_image_key, created_at
       from logs order by created_at desc limit $1`,
      [limit],
    );
    return rows;
  } catch (error) {
    console.error("admin logs full query failed:", error.message || "query_failed");
    const { rows } = await pool.query(
      `select id, owner_name, owner_tag, timestamp_ms, log_date, title, category, caffeine_mg,
        cafe, location_name, friend_names, '' as sticker, image_key, null as original_image_key, created_at
       from logs order by created_at desc limit $1`,
      [limit],
    );
    return rows;
  }
}

async function dayPayload(date, ownerId = null) {
  const normalizedDate = dateFromSlug(date);
  const ownerClause = ownerId ? "and owner_id = $2" : "";
  const params = ownerId ? [normalizedDate, ownerId] : [normalizedDate];
  const { rows } = await pool.query(
    `select id, owner_name, owner_tag, timestamp_ms, log_date, title, category, caffeine_mg,
      cafe, location_name, friend_names, sticker, image_key, original_image_key, created_at
     from logs where log_date = $1 ${ownerClause} order by timestamp_ms asc`,
    params,
  );
  return { date: normalizedDate, logs: rows.map(rowToLog) };
}

async function shareByToken(token) {
  const cleanToken = String(token || "").trim();
  if (!/^[a-zA-Z0-9_-]{16,80}$/.test(cleanToken)) {
    const error = new Error("Share not found");
    error.status = 404;
    throw error;
  }
  const { rows } = await pool.query(
    "select token, owner_id, owner_name, owner_tag, log_date, expires_at from day_shares where token = $1 and (expires_at is null or expires_at > now())",
    [cleanToken],
  );
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

function recordShareVisit(token) {
  pool.query("insert into share_visits (slug) values ($1)", [token]).catch((error) => {
    console.error("Share visit tracking failed:", error.message || "share_visit_failed");
  });
}

async function assertFriendTagAvailable(ownerTag, ownerId) {
  if (!ownerTag) return;
  const { rows } = await pool.query(
    "select owner_id from devices where owner_tag = $1 and owner_id <> $2 limit 1",
    [ownerTag, ownerId],
  );
  if (rows.length) {
    const error = new Error("username_taken");
    error.status = 409;
    throw error;
  }
}

function rowToLog(row) {
  return {
    id: row.id,
    ownerName: row.owner_name,
    ownerTag: row.owner_tag,
    timestamp: Number(row.timestamp_ms),
    date: normalizeDateValue(row.log_date),
    title: row.title,
    category: row.category,
    caffeineMg: row.caffeine_mg,
    cafe: row.cafe,
    locationName: row.location_name,
    friendNames: Array.isArray(row.friend_names) ? row.friend_names : [],
    sticker: row.sticker || "",
    imageUrl: row.image_key ? objectUrl(row.image_key) : null,
    originalImageUrl: row.original_image_key ? objectUrl(row.original_image_key) : null,
    createdAt: row.created_at,
  };
}

async function putObject(file, prefix) {
  const ext = extensionFor(file);
  const key = `${prefix}/${new Date().toISOString().slice(0, 10)}/${crypto.randomUUID()}${ext}`;
  if (s3) {
    try {
      await s3.send(new PutObjectCommand({
        Bucket: s3Bucket,
        Key: key,
        Body: file.buffer,
        ContentType: file.mimetype || "application/octet-stream",
      }));
      return { key, url: objectUrl(key) };
    } catch (error) {
      objectReady = false;
      lastObjectError = error.message || "object_upload_failed";
      console.error("Object upload failed; falling back to local object storage:", lastObjectError);
    }
  }
  await writeLocalObject(key, file.buffer);
  return { key, url: localObjectUrl(key) };
}

async function sendObject(key, res) {
  if (!/^[a-zA-Z0-9/_-]+\.(png|jpe?g|webp)$/i.test(key)) {
    res.status(404).end();
    return;
  }
  if (s3) {
    try {
      const object = await s3.send(new GetObjectCommand({ Bucket: s3Bucket, Key: key }));
      res.type(contentTypeFor(key));
      object.Body.pipe(res);
      return;
    } catch (error) {
      lastObjectError = error.message || "object_read_failed";
    }
  }
  res.type(contentTypeFor(key));
  res.sendFile(path.join(localObjectDir, key));
}

async function storageStats() {
  const local = await localStorageStats();
  if (!s3) {
    return {
      mode: "local",
      bytes: local.bytes,
      files: local.files,
      capacityBytes: storageCapacityBytes,
      percentUsed: storageCapacityBytes ? Math.round((local.bytes / storageCapacityBytes) * 1000) / 10 : null,
      path: localObjectDir,
    };
  }
  const remote = await s3StorageStats().catch(() => null);
  const bytes = remote?.bytes ?? local.bytes;
  const files = remote?.files ?? local.files;
  return {
    mode: remote ? "s3" : "local-fallback",
    bytes,
    files,
    capacityBytes: storageCapacityBytes,
    percentUsed: storageCapacityBytes ? Math.round((bytes / storageCapacityBytes) * 1000) / 10 : null,
    path: remote ? s3Bucket : localObjectDir,
  };
}

async function localStorageStats() {
  let bytes = 0;
  let files = 0;
  async function walk(dir) {
    const entries = await fs.readdir(dir, { withFileTypes: true }).catch(() => []);
    await Promise.all(entries.map(async (entry) => {
      const fullPath = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        await walk(fullPath);
      } else if (entry.isFile()) {
        const stat = await fs.stat(fullPath).catch(() => null);
        if (stat) {
          bytes += stat.size;
          files += 1;
        }
      }
    }));
  }
  await walk(localObjectDir);
  return { bytes, files };
}

async function s3StorageStats() {
  let bytes = 0;
  let files = 0;
  let ContinuationToken;
  do {
    const result = await s3.send(new ListObjectsV2Command({ Bucket: s3Bucket, ContinuationToken }));
    for (const item of result.Contents || []) {
      bytes += item.Size || 0;
      files += 1;
    }
    ContinuationToken = result.NextContinuationToken;
  } while (ContinuationToken);
  return { bytes, files };
}

function objectUrl(key) {
  return s3PublicBaseUrl && objectReady ? `${s3PublicBaseUrl}/${key}` : localObjectUrl(key);
}

function localObjectUrl(key) {
  return `/objects/${key}`;
}

async function writeLocalObject(key, buffer) {
  const target = path.join(localObjectDir, key);
  await fs.mkdir(path.dirname(target), { recursive: true });
  await fs.writeFile(target, buffer);
}

function renderSharePage(day, date, share = null) {
  const cards = day.logs.map((log) => `
    <article class="card">
      ${log.imageUrl ? `<img src="${escapeHtml(log.imageUrl)}" alt="">` : `<div class="placeholder">Nibbl</div>`}
      <div><strong>${escapeHtml(log.title || "Food + drink")}</strong><span>${escapeHtml(log.cafe || log.locationName || "Saved moment")}</span></div>
    </article>
  `).join("");
  const owner = share?.owner_name || share?.owner_tag || "";
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Nibbl ${escapeHtml(date)}</title><link rel="stylesheet" href="/styles.css"></head><body><main><section class="hero compact share-hero"><div class="mark logo-mark"><span class="cup-face"></span></div><p class="eyebrow">Shared diary day</p><h1>${escapeHtml(date)}</h1><p>${owner ? `${escapeHtml(owner)} shared this day. ` : ""}${day.logs.length ? `${day.logs.length} saved food and drink moments.` : "No public logs have synced for this day yet."}</p><div class="actions"><a class="button" href="intent://nibbl.z2hs.au/#Intent;scheme=https;package=au.z2hs.nibbl;S.browser_fallback_url=${encodeURIComponent("https://nibbl.z2hs.au/#waitlist")};end">Open in Nibbl</a><a class="button secondary" href="/#waitlist">Join waitlist</a></div></section><section class="share-grid">${cards}</section><section id="waitlist" class="install-strip waitlist-strip"><div><h2>Want early access?</h2><p>Join the Nibbl waitlist to save food and drink photos when more testing spots open.</p></div><a class="button" href="/#waitlist">Join waitlist</a></section><footer><span>Nibbl self-hosted at nibbl.z2hs.au</span><a href="/privacy.html">Privacy</a></footer></main></body></html>`;
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

function emailAddress(value) {
  const email = text(value, 254).toLowerCase();
  return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email) ? email : "";
}

function friendTag(value) {
  return text(value, 40).toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 10);
}

function dateFromSlug(slug) {
  const raw = String(slug || "").trim();
  const compact = raw.split("-")[0];
  const candidate = /^\d{4}-\d{2}-\d{2}$/.test(raw)
    ? raw
    : /^\d{8}$/.test(compact)
      ? `${compact.slice(0, 4)}-${compact.slice(4, 6)}-${compact.slice(6, 8)}`
      : "";
  if (candidate) {
    const parsed = new Date(`${candidate}T00:00:00.000Z`);
    if (!Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === candidate) {
      return candidate;
    }
  }
  const error = new Error("invalid_date");
  error.status = 400;
  throw error;
}

function normalizeDateValue(value) {
  if (value instanceof Date) {
    const year = value.getFullYear();
    const month = String(value.getMonth() + 1).padStart(2, "0");
    const day = String(value.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  }
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
