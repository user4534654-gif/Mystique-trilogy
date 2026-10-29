<#
    Applies the mod to the apktool project of chapter one.

    Chapter one still has readable xml layouts, so its controls live in the
    layout while the other chapters add them from code. This script updates the
    layout, registers the two ids it uses, patches QuestEngine and
    InventoryItem, and updates the manifest.

    Usage: powershell -ExecutionPolicy Bypass -File patch-ch1.ps1 -Project <path>
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Project,
    [int]$TargetSdk = 34
)

$ErrorActionPreference = "Stop"

$engineSmali = "$Project\smali\com\bendroid\questengine\QuestEngine.smali"
$itemSmali = "$Project\smali\com\bendroid\questengine\logic\inventory\InventoryItem.smali"
$layoutPath = "$Project\res\layout\main.xml"
$idsPath = "$Project\res\values\ids.xml"
$publicPath = "$Project\res\values\public.xml"
$manifestPath = "$Project\AndroidManifest.xml"

foreach ($f in @($engineSmali, $itemSmali, $layoutPath, $idsPath, $publicPath, $manifestPath)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "missing $f" }
}

function Save-IfChanged($path, $text, $label) {
    $old = Get-Content -LiteralPath $path -Raw
    if ($old -eq $text) {
        Write-Host "  $label : already patched"
        return
    }
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "  $label : patched"
}

# ------------------------------------------------ InventoryItem: dp on any density
# The item size was a hardcoded pixel value, so the inventory was tiny on a
# phone with a high density. The constructor now converts it from dp.
$text = Get-Content -LiteralPath $itemSmali -Raw
if ($text -notmatch 'InventoryItem;->dpToPx') {
    $anchor = '    const/16 v1, 0x4b'
    if ($text -notmatch [regex]::Escape($anchor)) { throw "InventoryItem anchor not found" }
    $call = @'
    const/16 v1, 0x4b
    invoke-static {p1, v1}, Lcom/bendroid/questengine/logic/inventory/InventoryItem;->dpToPx(Landroid/content/Context;I)I
    move-result v1
'@
    $text = $text.Replace($anchor, $call)

    $method = @'

.method public static dpToPx(Landroid/content/Context;I)I
    .locals 3
    .param p0, "context"
    .param p1, "dp"

    .prologue
    invoke-virtual {p0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;
    move-result-object v0
    invoke-virtual {v0}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;
    move-result-object v0
    const/4 v1, 0x0
    int-to-float v2, p1
    invoke-static {v1, v2, v0}, Landroid/util/TypedValue;->applyDimension(IFLandroid/util/DisplayMetrics;)F
    move-result v0
    float-to-int v0, v0
    return v0
.end method
'@
    $text = $text.TrimEnd() + "`r`n" + $method.TrimStart() + "`r`n"
    Save-IfChanged $itemSmali $text "InventoryItem dp size"
}

# ------------------------------------- QuestEngine: show/hide the analog stick
$text = Get-Content -LiteralPath $engineSmali -Raw
if ($text -notmatch ':cond_show_forward') {
    foreach ($pair in @(@('showForward', 'const/4 v1, 0x0', 'cond_show_forward'), @('hideForward', 'const/16 v1, 0x8', 'cond_hide_forward'))) {
        $name = $pair[0]
        $value = $pair[1]
        $label = $pair[2]
        $start = $text.IndexOf(".method public $name()V")
        if ($start -lt 0) { throw "$name not found" }
        $end = $text.IndexOf(".end method", $start) + ".end method".Length
        $replacement = @"
.method public $name()V
    .locals 2

    .prologue
    $value

    const v0, 0x7f07000e

    invoke-virtual {p0, v0}, Lcom/bendroid/questengine/QuestEngine;->findViewById(I)Landroid/view/View;

    move-result-object v0

    if-eqz v0, :$label

    .local v0, "pad":Landroid/view/View;
    invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V

    :$label
    return-void
.end method
"@
        $text = $text.Substring(0, $start) + $replacement + $text.Substring($end)
    }
    Save-IfChanged $engineSmali $text "QuestEngine show/hideForward"
}

# ------------------------------- QuestEngine: immersive fullscreen in onCreate
$text = Get-Content -LiteralPath $engineSmali -Raw
if ($text -notmatch 'SYSTEM_UI_FLAG_IMMERSIVE_STICKY') {
    $block = @'
    invoke-virtual {p0}, Lcom/bendroid/questengine/QuestEngine;->getWindow()Landroid/view/Window;
    move-result-object v5

    invoke-virtual {v5}, Landroid/view/Window;->getDecorView()Landroid/view/View;
    move-result-object v5

    sget v6, Landroid/view/View;->SYSTEM_UI_FLAG_IMMERSIVE_STICKY:I
    sget v2, Landroid/view/View;->SYSTEM_UI_FLAG_LAYOUT_STABLE:I
    or-int/2addr v6, v2
    sget v2, Landroid/view/View;->SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN:I
    or-int/2addr v6, v2
    sget v2, Landroid/view/View;->SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION:I
    or-int/2addr v6, v2
    sget v2, Landroid/view/View;->SYSTEM_UI_FLAG_FULLSCREEN:I
    or-int/2addr v6, v2
    sget v2, Landroid/view/View;->SYSTEM_UI_FLAG_HIDE_NAVIGATION:I
    or-int/2addr v6, v2

    invoke-virtual {v5, v6}, Landroid/view/View;->setSystemUiVisibility(I)V

'@
    $start = $text.IndexOf(".method public onCreate(Landroid/os/Bundle;)V")
    if ($start -lt 0) { throw "onCreate not found" }
    $end = $text.IndexOf(".end method", $start)
    $method = $text.Substring($start, $end - $start)
    $anchor = "->setContentView(I)V"
    $at = $method.IndexOf($anchor)
    if ($at -lt 0) { throw "setContentView not found" }
    $line = [regex]::Match($method.Substring($at), '[\r\n]+\s*\.line \d+[\r\n]+')
    if (-not $line.Success) { throw "line marker after setContentView not found" }
    $method = $method.Insert($at + $line.Index + $line.Length, $block)
    $locals = [regex]::Match($method, '\.locals (\d+)')
    if ($locals.Success -and [int]$locals.Groups[1].Value -lt 7) {
        $method = $method.Remove($locals.Index, $locals.Length).Insert($locals.Index, '.locals 7')
    }
    $text = $text.Substring(0, $start) + $method + $text.Substring($end)
    Save-IfChanged $engineSmali $text "QuestEngine immersive fullscreen"
}

# --------------------------------------------------------------- the two new ids
$ids = Get-Content -LiteralPath $idsPath -Raw
if ($ids -notmatch 'name="move_pad"') {
    $ids = $ids.Replace('</resources>', "    <id name=`"move_pad`" />`r`n    <id name=`"inventory_btn`" />`r`n</resources>")
    Save-IfChanged $idsPath $ids "ids.xml"
}
$public = Get-Content -LiteralPath $publicPath -Raw
if ($public -notmatch 'name="move_pad"') {
    $public = $public.Replace('</resources>', "    <public type=`"id`" name=`"move_pad`" id=`"0x7f07000e`" />`r`n    <public type=`"id`" name=`"inventory_btn`" id=`"0x7f07000f`" />`r`n</resources>")
    Save-IfChanged $publicPath $public "public.xml"
}

# ------------------------------------------------------------------ the layout
$layout = Get-Content -LiteralPath $layoutPath -Raw
if ($layout -notmatch 'move_pad') {
    # the original up and down buttons are replaced by the analog stick
    $layout = [regex]::Replace($layout, '(?m)^[ \t]*<com\.bendroid\.questengine\.logic\.ForwardButton[^\r\n]*\r?\n', '')
    $layout = [regex]::Replace($layout, '(?m)^[ \t]*<com\.bendroid\.questengine\.logic\.ReverseButton[^\r\n]*\r?\n', '')

    $views = @'
        <!-- Virtual analog stick for walking. Dragging the knob gives all eight
             directions including the diagonals with a single finger. -->
        <com.bendroid.questengine.logic.MoveStick
            android:id="@+id/move_pad"
            android:visibility="gone"
            android:layout_gravity="bottom|left"
            android:layout_width="170dp"
            android:layout_height="170dp"
            android:layout_marginLeft="6dp"
            android:layout_marginBottom="6dp"/>
        <com.bendroid.questengine.logic.InventoryButton
            android:id="@+id/inventory_btn"
            android:layout_gravity="top|right"
            android:layout_width="54dp"
            android:layout_height="54dp"
            android:layout_marginTop="10dp"
            android:layout_marginRight="10dp"/>
'@
    $layout = [regex]::Replace($layout, '(?m)(^[ \t]*<com\.bendroid\.questengine\.logic\.BackIconButton[^\r\n]*\r?\n)', "`$1$views", 1)

    # every size of this layout was stored in pixels
    $layout = [regex]::Replace($layout, '(\d+(?:\.\d+)?)px', '$1dp')
    # the panels were too opaque for the small screens
    $layout = $layout.Replace('#8333', '#3333')
    # the inventory starts below the top bar
    $layout = $layout -replace '(android:id="@id/inventory"[^\r\n]*?)( android:layout_weight=)', '$1 android:layout_marginTop="68dp"$2'

    Save-IfChanged $layoutPath $layout "layout main.xml"
}

# ---------------------------------------------------------------- the manifest
$manifest = Get-Content -LiteralPath $manifestPath -Raw
if ($manifest -notmatch 'android:largeHeap="true"') {
    $manifestLine = [regex]::Match($manifest, '<manifest[^>]*>')
    if (-not $manifestLine.Success) { throw "manifest element not found" }
    $header = "`r`n    <uses-sdk android:minSdkVersion=`"4`" android:targetSdkVersion=`"$TargetSdk`"/>" +
              "`r`n    <supports-screens" +
              "`r`n        android:resizeable=`"true`"" +
              "`r`n        android:smallScreens=`"true`"" +
              "`r`n        android:normalScreens=`"true`"" +
              "`r`n        android:largeScreens=`"true`"" +
              "`r`n        android:xlargeScreens=`"true`"" +
              "`r`n        android:anyDensity=`"true`"/>"
    $manifest = $manifest.Replace($manifestLine.Value, $manifestLine.Value + $header)
    $manifest = [regex]::Replace($manifest, '<application ', '<application android:largeHeap="true" ', 1)
    $manifest = $manifest -replace 'android:configChanges="keyboardHidden\|orientation"', 'android:configChanges="orientation|screenSize|smallestScreenSize|uiMode|keyboardHidden"'
    $manifest = $manifest -replace 'android:screenOrientation="landscape"', 'android:screenOrientation="sensorLandscape"'
    $manifest = [regex]::Replace($manifest, '(android:name="\.Mystique\d+"[^>]*?)>', '$1 android:resizeableActivity="true" android:exported="true">', 1)
    Save-IfChanged $manifestPath $manifest "AndroidManifest"
}

Write-Host "patched $Project" -ForegroundColor Green