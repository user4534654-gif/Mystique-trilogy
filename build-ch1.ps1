<#
    Builds the modded apk of chapter one from the stock apk.

    The stock apk is never part of this repository. Pass the one you own with
    -Original; the script decodes it, patches the layout and the smali, drops
    the mod classes in, rebuilds and signs the result.

    Usage:
      powershell -ExecutionPolicy Bypass -File build-ch1.ps1 -Original C:\path\mystique-chapter-1-foetus-1-4.apk

    The stock apk is checked against originals.sha256, so a build from a wrong
    file stops before anything is produced.
#>

[CmdletBinding()]
param(
    [string]$Original,
    [string]$Apktool = "",
    [string]$Keystore = "",
    [string]$StorePass = "mystique",
    [string]$Work = "",
    [string]$OutDir = "",
    [int]$VersionCode = 25,
    [string]$VersionName = "1.4.1",
    [switch]$Verify
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $Apktool) { $Apktool = Join-Path $root "apktool.jar" }
if (-not $Keystore) { $Keystore = Join-Path $root "mystique-mod.keystore" }
if (-not $Work) { $Work = Join-Path $root "build-ch1" }
if (-not $OutDir) { $OutDir = Join-Path $root "dist" }

if (-not $Original) { $Original = "$root\originals\mystique-chapter-1-foetus-1-4.apk" }
if (-not (Test-Path -LiteralPath $Original)) { throw "stock apk not found: $Original (pass -Original)" }
if (-not (Test-Path -LiteralPath $Apktool)) { throw "apktool.jar not found: $Apktool (pass -Apktool)" }
if (-not (Test-Path -LiteralPath $Keystore)) { throw "keystore not found: $Keystore (pass -Keystore)" }

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$androidJar = "$sdk\platforms\android-29\android.jar"
$javac = "C:\Program Files\Java\jdk-27\bin\javac.exe"
$jar = "C:\Program Files\Java\jdk-27\bin\jar.exe"
$d8 = "$sdk\build-tools\36.1.0\d8.bat"
$zipalign = "$sdk\build-tools\36.1.0\zipalign.exe"
$apksigner = "$sdk\build-tools\36.1.0\apksigner.bat"

$project = "$Work\project"
$shell = "$Work\shell.apk"
$srcDir = "$root\src"
$stubDir = "$root\stubs"

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

# ------------------------------------------------------- 0. check the original
Step "0/8 check the stock apk"
$sumFile = "$root\originals.sha256"
if (Test-Path -LiteralPath $sumFile) {
    $name = [System.IO.Path]::GetFileName($Original)
    $expected = (Get-Content -LiteralPath $sumFile | Where-Object { $_ -match [regex]::Escape($name) } | Select-Object -First 1)
    if ($expected) {
        $expectedHash = ($expected -split '\s+')[0].ToUpper()
        $actualHash = (Get-FileHash -LiteralPath $Original -Algorithm SHA256).Hash.ToUpper()
        Write-Host "  sha256 $actualHash"
        if ($expectedHash -ne $actualHash) { throw "this is not the expected stock apk: expected $expectedHash" }
        Write-Host "  matches originals.sha256" -ForegroundColor Green
    }
}
Write-Host "  size $((Get-Item -LiteralPath $Original).Length) bytes"

# ------------------------------------------------------------- 1. decode stock
Step "1/8 decode the stock apk"
Remove-Item -Recurse -Force $project -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Work | Out-Null
& java -jar $Apktool d -f $Original -o $project | Out-Null
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$project\AndroidManifest.xml")) { throw "apktool decode failed" }

# ------------------------------------------------------------------ 2. patch
Step "2/8 apply the layout and smali patch"
& powershell -ExecutionPolicy Bypass -File "$root\patch-ch1.ps1" -Project $project
if ($LASTEXITCODE -ne 0) { throw "patch failed" }

# --------------------------------------------------------- 3. stamp the version
Step "3/8 stamp version $VersionName ($VersionCode)"
$yml = Get-Content -LiteralPath "$project\apktool.yml" -Raw
$yml = [regex]::Replace($yml, '(?m)^  versionCode: \d+', "  versionCode: $VersionCode")
$yml = [regex]::Replace($yml, '(?m)^  versionName: .*', "  versionName: $VersionName")
[System.IO.File]::WriteAllText("$project\apktool.yml", $yml, (New-Object System.Text.UTF8Encoding($false)))
# chapter one keeps the version in the manifest as well
$mf = Get-Content -LiteralPath "$project\AndroidManifest.xml" -Raw
$mf = [regex]::Replace($mf, 'android:versionCode="\d+"', "android:versionCode=`"$VersionCode`"")
$mf = [regex]::Replace($mf, 'android:versionName="[^"]*"', "android:versionName=`"$VersionName`"")
[System.IO.File]::WriteAllText("$project\AndroidManifest.xml", $mf, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "  versionCode $VersionCode, versionName $VersionName"

# ------------------------------------------------------------------ 4. stubs
Step "4/8 compile stubs"
Remove-Item -Recurse -Force "$Work\stubs-classes" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$Work\stubs-classes" | Out-Null
$stubs = Get-ChildItem -Path $stubDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -nowarn --release 11 -cp $androidJar -d "$Work\stubs-classes" @stubs
if ($LASTEXITCODE -ne 0) { throw "stub compilation failed" }
Remove-Item "$Work\stubs.jar" -ErrorAction SilentlyContinue
& $jar cf "$Work\stubs.jar" -C "$Work\stubs-classes" .
if ($LASTEXITCODE -ne 0) { throw "stub jar failed" }

# ------------------------------------------------------------------ 5. sources
Step "5/8 compile the mod and turn it back into smali"
Remove-Item -Recurse -Force "$Work\classes" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$Work\classes" | Out-Null
$srcs = Get-ChildItem -Path $srcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -nowarn --release 11 -cp "$androidJar;$Work\stubs.jar" -d "$Work\classes" @srcs
if ($LASTEXITCODE -ne 0) { throw "mod compilation failed" }

Remove-Item -Recurse -Force "$Work\dex" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$Work\dex" | Out-Null
$classFiles = Get-ChildItem -Path "$Work\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --lib $androidJar --lib "$Work\stubs.jar" --min-api 21 --output "$Work\dex" @classFiles
if ($LASTEXITCODE -ne 0 -or -not (Test-Path "$Work\dex\classes.dex")) { throw "d8 failed" }

Copy-Item -LiteralPath $Original -Destination $shell -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($shell, 'Update')
$old = $zip.GetEntry('classes.dex')
if ($old) { $old.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, "$Work\dex\classes.dex", 'classes.dex',
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

Remove-Item -Recurse -Force "$Work\shell_dec" -ErrorAction SilentlyContinue
& java -jar $Apktool d -f -r $shell -o "$Work\shell_dec" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "shell decode failed" }

$copied = 0
# only the classes of the mod are taken over
$myClasses = @(Get-ChildItem -Path "$Work\classes" -Recurse -Filter *.class | ForEach-Object {
    $rel = $_.FullName.Substring("$Work\classes\".Length)
    "smali\" + $rel.Replace('\', '/').Replace('.class', '.smali')
})
foreach ($rel in $myClasses) {
    $from = "$Work\shell_dec\$rel"
    if (-not (Test-Path -LiteralPath $from)) { continue }
    $dest = "$project\$rel"
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    Copy-Item -LiteralPath $from -Destination $dest -Force
    Write-Host "  smali: $rel"
    $copied++
}
if ($copied -eq 0) { throw "no smali produced" }

# ---------------------------------------------------------------- 6. build
Step "6/8 apktool build"
$unsigned = "$Work\unsigned.apk"
Remove-Item -Force $unsigned -ErrorAction SilentlyContinue
& java -jar $Apktool b -f $project -o $unsigned
if ($LASTEXITCODE -ne 0) { throw "apktool build failed" }

# ---------------------------------------------------------------- 7. sign
Step "7/8 zipalign and sign"
$aligned = "$Work\aligned.apk"
Remove-Item -Force $aligned -ErrorAction SilentlyContinue
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$outApk = "$OutDir\Mystique-Ch1-Movement-Mod-$VersionName.apk"
Remove-Item -Force $outApk -ErrorAction SilentlyContinue
Copy-Item -LiteralPath $aligned -Destination $outApk -Force
& $apksigner sign --ks $Keystore --ks-pass "pass:$StorePass" --key-pass "pass:$StorePass" --v1-signing-enabled true --v2-signing-enabled true --v4-signing-enabled false $outApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Remove-Item "$outApk.idsig" -ErrorAction SilentlyContinue

$previous = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $cert = (& $apksigner verify --print-certs $outApk 2>&1 | Select-String -Pattern "certificate DN" | Select-Object -First 1)
} finally {
    $ErrorActionPreference = $previous
}
if (-not $cert) { throw "signature check failed" }
Write-Host "  $($cert.Line.Trim())"

if ($Verify) {
    Step "8/8 verify the references"
    & powershell -ExecutionPolicy Bypass -File "$root\verify-references.ps1" -Apk $outApk -Targets "Lcom/bendroid/questengine/logic/InputProcessor;~Lcom/bendroid/questengine/logic/MoveStick;~Lcom/bendroid/questengine/logic/InventoryButton;~Lcom/bendroid/questengine/QuestEngine;"
    if ($LASTEXITCODE -ne 0) { throw "reference check failed" }
}

$hash = (Get-FileHash -LiteralPath $outApk -Algorithm SHA256).Hash
Write-Host "`nBuilt: $outApk" -ForegroundColor Green
Write-Host "size  : $((Get-Item -LiteralPath $outApk).Length) bytes"
Write-Host "sha256: $hash"
Write-Host "`nInstall with: adb install -r `"$outApk`"" -ForegroundColor Cyan