# Nibbl TrueNAS Self-Host

Domains are fixed to:

- App/site: `https://nibbl.z2hs.au`
- API/admin: `https://api.nibbl.z2hs.au`

This stack runs PocketBase on host port `5100`. Your existing Caddy routes both Nibbl domains to `172.20.20.251:5100`.

## TrueNAS Scale

1. Create this dataset layout:
   - `/mnt/mainstorage/apps/nibbl`
   - `/mnt/mainstorage/apps/nibbl/pb_data`
   - `/mnt/mainstorage/apps/nibbl/pb_public`
2. Put the files in `deploy/truenas` into `/mnt/mainstorage/apps/nibbl`.
3. Create a local `.env` beside `docker-compose.yml` with a new ingest key:

```env
NIBBL_INGEST_KEY=replace-with-a-long-random-value
```

Build the Android APK with the same value:

```powershell
.\gradlew.bat assembleDebug -PNIBBL_INGEST_KEY="your-long-random-value"
```

4. Point DNS:
   - `nibbl.z2hs.au` -> your TrueNAS IP/public proxy
   - `api.nibbl.z2hs.au` -> your TrueNAS IP/public proxy
5. In TrueNAS Apps, create a custom app from `docker-compose.yml`.
6. Open the admin UI:
   - `https://api.nibbl.z2hs.au/_/`
7. Health check:
   - `https://api.nibbl.z2hs.au/api/health`

## Admin Setup

PocketBase asks you to create the first admin account the first time you open `/_/`.

After that, create these collections:

- `profiles`: display name, avatar color.
- `crew`: owner, display name, color, favorite flag.
- `logs`: owner, date, title, category, caffeine, cafe, location, image.
- `day_shares`: owner, date, token, access mode, expiry.

Current Android behavior creates signed day invite URLs locally. The backend is ready for the next step: resolving those invite URLs into real shared day pages and syncing logs across users.

## External Caddy

Your external Caddy config should be:

```caddyfile
nibbl.z2hs.au {
    reverse_proxy 172.20.20.251:5100
}
api.nibbl.z2hs.au {
    reverse_proxy 172.20.20.251:5100
}
```

## Local Script

From this folder on a Docker host:

```powershell
.\scripts\run-admin-ui.ps1
```

or:

```bash
docker compose up -d
```
