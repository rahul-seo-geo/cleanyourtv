# Send private dashboard settings to the launcher after installing it. Values are never printed.
param(
    [Parameter(Position = 0)][string]$Settings,
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
if (-not $Settings) { $Settings = Join-Path $PSScriptRoot 'dashboard.txt' }
if (-not (Test-Path $Settings -PathType Leaf)) { throw 'no dashboard file (copy dashboard.example.txt to dashboard.txt and edit it)' }
$Settings = (Resolve-Path $Settings).Path
Set-Location (Join-Path (Split-Path $PSScriptRoot -Parent) 'tools')
$py = 'venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'set up tools\venv first' }
$pkg = 'com.gathin.tvhome'
foreach ($properties in @((Join-Path $PSScriptRoot 'gradle.properties'), (Join-Path $PSScriptRoot 'local.properties'))) {
    if (Test-Path $properties -PathType Leaf) {
        foreach ($line in (Get-Content $properties)) {
            if ($line -match '^\s*launcherApplicationId\s*=\s*(\S+)\s*$') { $pkg = $Matches[1]; break }
        }
    }
}
$tmp = '/data/local/tmp/tv-dashboard.txt'
function Tv([string]$cmd) { & $py -I tv.py $cmd; if ($LASTEXITCODE) { throw 'tv.py failed' } }
if (-not ((Tv "pm path $pkg; true") -match 'package:')) { throw 'Launcher is not installed' }
Tv "appops set $pkg AUTO_START allow 2>/dev/null; true" | Out-Null
& $py -I b64push.py $Settings $tmp | Out-Null
if ($LASTEXITCODE) { throw 'sending dashboard failed' }
Tv "content write --uri content://$pkg.files/dashboard.txt < $tmp; rm -f $tmp" | Out-Null
Write-Host 'Launcher dashboard sent. Values are not printed.'
