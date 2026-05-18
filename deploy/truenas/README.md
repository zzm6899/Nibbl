# Nibbl TrueNAS Self-Host

Domains are fixed to:

- App/site/share pages: `https://nibbl.z2hs.au`
- API/admin: `https://api.nibbl.z2hs.au`

This stack runs the Nibbl backend on host port `5100`, Postgres for authoritative data, and MinIO for object storage. Your existing Caddy routes both Nibbl domains to `172.20.20.251:5100`.

## TrueNAS Scale

1. Create this dataset layout:
   - `/mnt/mainstorage/apps/nibbl`
   - `/mnt/mainstorage/apps/nibbl/postgres`
   - `/mnt/mainstorage/apps/nibbl/objects`
2. Put `deploy/truenas/docker-compose.yml` and your `.env` into `/mnt/mainstorage/apps/nibbl`.
3. Create `.env` beside `docker-compose.yml`:

```env
NIBBL_ADMIN_PASSWORD=replace-with-an-admin-password
POSTGRES_PASSWORD=replace-with-a-postgres-password
MINIO_ROOT_USER=nibblminio
MINIO_ROOT_PASSWORD=replace-with-a-minio-password
```

4. In TrueNAS Apps, create or update the custom app from `docker-compose.yml`.
5. Open:
   - Public site: `https://nibbl.z2hs.au/`
   - Share links: `https://nibbl.z2hs.au/?i=YYYYMMDD-token`
   - Admin UI: `https://api.nibbl.z2hs.au/admin.html`
   - Health: `https://api.nibbl.z2hs.au/api/health`
   - MinIO console: `http://<truenas-ip>:5101`

## Reload

```bash
cd /mnt/mainstorage/apps/nibbl
docker compose pull
docker compose up -d --force-recreate
```

## External Caddy

```caddyfile
nibbl.z2hs.au {
    reverse_proxy 172.20.20.251:5100
}

api.nibbl.z2hs.au {
    reverse_proxy 172.20.20.251:5100
}
```
