param(
    [string]$Directory = "$PSScriptRoot\..\release-assets"
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\.."
$checks = Join-Path $Directory "checksums.txt"
$signature = Join-Path $Directory "checksums.txt.minisig"
$publicKey = if ($env:MINISIGN_PUBLIC_KEY) { $env:MINISIGN_PUBLIC_KEY } else { Join-Path $root "release\minisign.pub" }
$minisign = Get-Command minisign -ErrorAction SilentlyContinue

if (-not (Test-Path $checks)) { throw "missing checksums.txt" }
if (-not (Test-Path $signature)) { throw "missing checksums.txt.minisig" }
if (-not (Test-Path $publicKey)) { throw "missing Minisign public key" }
if ($null -eq $minisign) { throw "minisign is not installed" }

Write-Host "== checksum files =="
$lines = Get-Content $checks | Where-Object { $_.Trim() -ne "" }
foreach ($line in $lines) {
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "malformed checksum line: $line" }
    $expected = $Matches[1]
    $name = $Matches[2]
    $path = Join-Path $Directory $name
    if (-not (Test-Path $path)) { throw "missing artifact: $name" }
    $actual = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "checksum mismatch: $name" }
    Write-Host "OK $name"
}

Write-Host "== Minisign signature =="
& $minisign.Source -V -p $publicKey -m $checks -x $signature
if ($LASTEXITCODE -ne 0) { throw "Minisign verification failed" }

Write-Host "release verification: PASS"
