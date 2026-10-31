# Send private settings to News after installing the app. Values are never printed.
param(
    [Parameter(Position = 0)][string]$Settings,
    [string]$TvHost = $env:TV_HOST
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
if (-not $Settings) { $Settings = Join-Path $PSScriptRoot 'settings.txt' }
if (-not (Test-Path $Settings -PathType Leaf)) { throw 'no settings file (copy settings.example.txt to settings.txt and edit it)' }
$Settings = (Resolve-Path $Settings).Path
Set-Location (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'tools')
$py = 'venv\Scripts\python.exe'
if (-not (Test-Path $py)) { throw 'set up tools\venv first' }
$pkg = 'com.gathin.news'
$tmp = '/data/local/tmp/news-settings.txt'
function Tv([string]$cmd) { & $py -I tv.py $cmd; if ($LASTEXITCODE) { throw 'tv.py failed' } }
if (-not ((Tv "pm path $pkg; true") -match 'package:')) { throw 'News is not installed' }
Tv "appops set $pkg AUTO_START allow 2>/dev/null; true" | Out-Null
& $py -I b64push.py $Settings $tmp | Out-Null
if ($LASTEXITCODE) { throw 'sending settings failed' }
Tv "content write --uri content://$pkg.files/settings.txt < $tmp; rm -f $tmp" | Out-Null
Write-Host 'News settings sent. Values are not printed.'
