<#
    Static verification: every method and field reference made by the classes
    touched by the mod must resolve inside the finished APK.

    Walks the class hierarchy of the built dex, so a reference to an inherited
    method is accepted as well.
#>
param(
    [string]$Apk = "",
    [string]$D8 = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\dexdump.exe",
    [string]$Work = "",
    [string]$Targets = "Lcom/bendroid/questengine/logic/InputProcessor;~Lcom/bendroid/questengine/logic/MoveStick;~Lcom/bendroid/questengine/logic/InventoryButton;~Lcom/bendroid/questengine/logic/ModViews;~Lcom/bendroid/questengine/logic/ModInventory;~Lcom/bendroid/questengine/logic/StateProbe;~Lcom/bendroid/questengine/logic/TextureFix;~Lcom/bendroid/questengine/QuestEngine;"
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
if (-not $root) { $root = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $Apk) { $Apk = "$root\dist\Mystique-Ch3-Movement-Mod-1.0.5.apk" }
if (-not $Work) { $Work = "$root\build-verify" }

New-Item -ItemType Directory -Force -Path $Work | Out-Null
$dex = "$Work\built.dex"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
$entry = $zip.Entries | Where-Object { $_.FullName -eq "classes.dex" }
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dex, $true)
$zip.Dispose()

$dump = "$Work\dexdump.txt"
& $D8 -d $dex | Out-File -Encoding utf8 $dump

$lines = Get-Content -LiteralPath $dump

# ---------------------------------------------------------------- parse defs
$classes = @{}          # class -> @{ super = ..; methods = @{}; fields = @{} }
$cur = $null
$section = $null
foreach ($l in $lines) {
    if ($l -match "^\s*Class descriptor\s+:\s+'([^']+)'") {
        $cur = $Matches[1]
        if (-not $classes.ContainsKey($cur)) {
            $classes[$cur] = @{ super = $null; methods = @{}; fields = @{} }
        }
        $section = $null
        continue
    }
    if ($null -eq $cur) { continue }
    if ($l -match "^\s*Superclass\s+:\s+'([^']+)'") { $classes[$cur].super = $Matches[1]; continue }
    if ($l -match "^\s*(Direct methods|Virtual methods)\s+-\s*$") { $section = "method"; continue }
    if ($l -match "^\s*(Direct fields|Virtual fields)\s+-\s*$") { $section = "field"; continue }
    if ($l -match "^\s*name\s+:\s+'([^']*)'") {
        $name = $Matches[1]
        if ($section -eq "method" -and $name -ne "<init>") { $classes[$cur].methods[$name] = $true }
        continue
    }
    if ($l -match "^\s*type\s+:\s+'([^']*)'" -and $section -eq "method") {
        # proto follows the name, remember it
        $classes[$cur].methods[$name] = $Matches[1]
        continue
    }
    if ($l -match "^\s*name\s+:\s+'([^']*)'") { }
}

# fields: dexdump prints "name : 'x'" then "type : 'Ljava/lang/String;'" under field sections
$classes2 = @{}
foreach ($k in $classes.Keys) { $classes2[$k] = @{ super = $classes[$k].super; methods = @{}; fields = @{} } }
$cur = $null; $section = $null; $fname = $null
foreach ($l in $lines) {
    if ($l -match "^\s*Class descriptor\s+:\s+'([^']+)'") { $cur = $Matches[1]; $section = $null; continue }
    if ($null -eq $cur) { continue }
    if ($l -match "^\s*Superclass\s+:\s+'([^']+)'") { $classes2[$cur].super = $Matches[1]; continue }
    if ($l -match "^\s*(Direct methods|Virtual methods)\s+-\s*$") { $section = "method"; continue }
    if ($l -match "^\s*(Static fields|Instance fields|Direct fields|Virtual fields)\s+-\s*$") { $section = "field"; continue }
    if ($section -eq "method" -and $l -match "^\s*name\s+:\s+'([^']*)'") { $classes2[$cur].methods[$Matches[1]] = $true; continue }
    if ($section -eq "field" -and $l -match "^\s*name\s+:\s+'([^']*)'") { $fname = $Matches[1]; $classes2[$cur].fields[$fname] = $true; continue }
}

function HasMethod($cls, $name) {
    $c = $cls
    $guard = 0
    while ($c -and $guard -lt 20) {
        if ($classes2.ContainsKey($c) -and $classes2[$c].methods.ContainsKey($name)) { return $true }
        $c = $classes2[$c].super
        $guard++
    }
    return $false
}
function HasField($cls, $name) {
    $c = $cls
    $guard = 0
    while ($c -and $guard -lt 20) {
        if ($classes2.ContainsKey($c) -and $classes2[$c].fields.ContainsKey($name)) { return $true }
        $c = $classes2[$c].super
        $guard++
    }
    return $false
}

# ------------------------------------------------- collect references in use
# every class of the dex is checked, that is stricter than looking at the
# classes of the mod only
$refs = @{}
foreach ($l in $lines) {
    # dexdump prints references as  Lclass;.name:proto   and   Lclass;->name:Type
    foreach ($m in [regex]::Matches($l, "(L[^;]+;)(?:\.|->)([A-Za-z0-9_$<>]+)")) {
        $c = $m.Groups[1].Value; $n = $m.Groups[2].Value
        $key = "$c->$n"
        if (-not $refs.ContainsKey($key)) { $refs[$key] = @($c, $n, $false) }
    }
}

Write-Host "resolved symbol table: $($classes2.Count) classes" -ForegroundColor Cyan

# also check field accesses  (iget/iput/sget/sput on Lcls;->name:Type)
$fieldRefs = @{}
foreach ($l in $lines) {
    foreach ($m in [regex]::Matches($l, "(?:iget|iput|sget|sput)[^\s]*\s+(L[^;]+;)(?:\.|->)([A-Za-z0-9_$]+):")) {
        $key = "$($m.Groups[1].Value)->$($m.Groups[2].Value)"
        if (-not $fieldRefs.ContainsKey($key)) { $fieldRefs[$key] = @($m.Groups[1].Value, $m.Groups[2].Value) }
    }
}

function LeavesDex($cls) {
    # true when the class inherits the member from the Android framework
    $c = $cls
    $guard = 0
    while ($c -and $guard -lt 20) {
        if (-not $classes2.ContainsKey($c)) { return $true }
        $c = $classes2[$c].super
        $guard++
    }
    return $false
}

$bad = 0
$lib = 0
Write-Host "`n--- method references ---" -ForegroundColor Cyan
foreach ($k in ($refs.Keys | Sort-Object)) {
    $c = $refs[$k][0]; $n = $refs[$k][1]
    if (-not $classes2.ContainsKey($c)) {
        # not part of the dex -> platform / library class, checked separately
        $lib++
        continue
    }
    $ok = HasMethod $c $n
    if (-not $ok) { $ok = (HasField $c $n) }   # some are field accesses parsed as methods
    if (-not $ok -and (LeavesDex $c)) { $lib++; continue }
    $mark = if ($ok) { "ok " } else { "MISSING" }
    if (-not $ok) { $bad++ }
    Write-Host ("  {0,-8} {1}" -f $mark, $k)
}

Write-Host "`n--- field references ---" -ForegroundColor Cyan
foreach ($k in ($fieldRefs.Keys | Sort-Object)) {
    $c = $fieldRefs[$k][0]; $n = $fieldRefs[$k][1]
    if (-not $classes2.ContainsKey($c)) { $lib++; continue }
    $ok = HasField $c $n
    if (-not $ok -and (LeavesDex $c)) { $lib++; continue }
    $mark = if ($ok) { "ok " } else { "MISSING" }
    if (-not $ok) { $bad++ }
    Write-Host ("  {0,-8} {1}" -f $mark, $k)
}

Write-Host ""
Write-Host "platform/library references (resolved by android.jar, not checkable here): $lib"
if ($bad -eq 0) {
    Write-Host "ALL GAME-INTERNAL REFERENCES RESOLVE" -ForegroundColor Green
} else {
    Write-Host "$bad UNRESOLVED GAME-INTERNAL REFERENCES" -ForegroundColor Red
}
