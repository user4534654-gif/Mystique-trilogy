<#
    Tells you whether an apk is the build it claims to be.

    It prints the package, the version, the sha256 and the signature, and it
    looks for the classes of the mod inside the dex. With -Sums it also checks
    the file against a SHA256SUMS.txt.

    Usage:
      powershell -ExecutionPolicy Bypass -File verify-apk.ps1 -Apk .\dist\Mystique-Ch3-Movement-Mod-1.0.5.apk
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$BuildTools = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0",
    [string]$Sums = ""
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $Sums) { $Sums = Join-Path $root "SHA256SUMS.txt" }

if (-not (Test-Path -LiteralPath $Apk)) { throw "apk not found: $Apk" }

function Line($label, $value) { "{0,-12} {1}" -f $label, $value }

$apkItem = Get-Item -LiteralPath $Apk
$hash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
$name = $apkItem.Name

Write-Host ""
Write-Host $name -ForegroundColor Cyan
Write-Host (Line "size" "$($apkItem.Length) bytes")
Write-Host (Line "sha256" $hash)

$aapt = "$BuildTools\aapt2.exe"
if (Test-Path -LiteralPath $aapt) {
    $badging = (& $aapt dump badging $Apk 2>$null | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
    if ($badging) {
        $pkg = [regex]::Match($badging, "name='([^']+)'").Groups[1].Value
        $code = [regex]::Match($badging, "versionCode='([^']+)'").Groups[1].Value
        $ver = [regex]::Match($badging, "versionName='([^']+)'").Groups[1].Value
        Write-Host (Line "package" $pkg)
        Write-Host (Line "version" "$ver ($code)")
    }
}

# the mod classes that have to be inside the dex
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
try {
    $markers = @('StateProbe', 'ModViews', 'ModInventory', 'MoveStick', 'TextureFix', 'InventoryButton')
    $found = @{}
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -notmatch '^classes\d*\.dex$') { continue }
        $stream = $entry.Open()
        $ms = New-Object System.IO.MemoryStream
        $stream.CopyTo($ms)
        $stream.Dispose()
        $bytes = $ms.ToArray()
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        foreach ($m in $markers) {
            if ($text.Contains($m)) { $found[$m] = $true }
        }
    }
    if ($found.Count -gt 0) {
        Write-Host (Line "mod" ("yes: " + (($found.Keys | Sort-Object) -join ', '))) -ForegroundColor Green
    } else {
        Write-Host (Line "mod" "no mod classes found, this is probably the stock apk") -ForegroundColor Red
    }
} finally {
    $zip.Dispose()
}

$apksigner = "$BuildTools\apksigner.bat"
if (Test-Path -LiteralPath $apksigner) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $verify = (& $apksigner verify $Apk 2>&1)
        $ok = -not ($verify | Where-Object { $_ -match 'ERROR|DOES NOT VERIFY' })
        $dn = (& $apksigner verify --print-certs $Apk 2>&1 | Where-Object { $_ -match 'certificate DN' } | Select-Object -First 1)
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($ok) {
        Write-Host (Line "signature" "valid") -ForegroundColor Green
        if ($dn) { Write-Host (Line "" $dn.Trim()) }
    } else {
        Write-Host (Line "signature" "NOT VALID") -ForegroundColor Red
    }
}

if (Test-Path -LiteralPath $Sums) {
    $expected = Get-Content -LiteralPath $Sums | Where-Object { ($_ -split '\s+')[-1] -eq $name } | Select-Object -First 1
    if ($expected) {
        $expectedHash = ($expected -split '\s+')[0].ToUpper()
        if ($expectedHash -eq $hash) {
            Write-Host (Line "sums" "matches SHA256SUMS.txt") -ForegroundColor Green
        } else {
            Write-Host (Line "sums" "DOES NOT MATCH SHA256SUMS.txt") -ForegroundColor Red
        }
    } else {
        Write-Host (Line "sums" "not listed in SHA256SUMS.txt")
    }
}
Write-Host ""