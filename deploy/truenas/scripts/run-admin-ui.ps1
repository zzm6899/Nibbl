$ErrorActionPreference = "Stop"

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    docker compose up -d
    Write-Host "Nibbl backend is starting."
    Write-Host "Admin UI: https://api.nibbl.z2hs.au/admin.html"
    Write-Host "Health:   https://api.nibbl.z2hs.au/api/health"
    Start-Process "https://api.nibbl.z2hs.au/admin.html"
}
finally {
    Pop-Location
}
