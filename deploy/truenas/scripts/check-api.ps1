$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$health = Invoke-RestMethod -Uri "https://api.nibbl.z2hs.au/api/health" -TimeoutSec 15
$health | ConvertTo-Json -Depth 5
