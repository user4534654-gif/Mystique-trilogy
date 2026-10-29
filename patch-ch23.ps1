<#
    Applies the mod to the apktool project of chapter two or three.

    The layout of these chapters is stored as compiled binary xml and can not
    be edited, so the controls are added from code instead. That means the
    patch is done in the smali of the two classes that have to know about it:

      InputProcessor   gets a sideways component that is applied on top of the
                       forward movement, so walking diagonally works
      QuestEngine      installs the controls and shows/hides them together with
                       the original up and down buttons

    Usage: powershell -ExecutionPolicy Bypass -File patch-ch23.ps1 -Project <path>
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Project,
    [int]$TargetSdk = 28
)

$ErrorActionPreference = "Stop"

$inputSmali = "$Project\smali\com\bendroid\questengine\logic\InputProcessor.smali"
$engineSmali = "$Project\smali\com\bendroid\questengine\QuestEngine.smali"
$itemSmali = "$Project\smali\com\bendroid\questengine\logic\inventory\InventoryItem.smali"

foreach ($f in @($inputSmali, $engineSmali, $itemSmali)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "missing $f" }
}

function Save-IfChanged($path, $text, $label) {
    $old = Get-Content -LiteralPath $path -Raw
    if ($old -eq $text) {
        Write-Host "  $label : already patched"
        return
    }
    # no byte order mark, smali cannot be parsed with one
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "  $label : patched"
}

# ------------------------------------------------- InputProcessor: field
$text = Get-Content -LiteralPath $inputSmali -Raw
if ($text -notmatch '\.field private strafeDir:F') {
    $anchor = '.field private plus:Lcom/bendroid/global/math/Point3D;'
    if ($text -notmatch [regex]::Escape($anchor)) { throw "anchor for strafeDir field not found" }
    $text = $text -replace ([regex]::Escape($anchor)), ($anchor + "`r`n.field private strafeDir:F")
    Save-IfChanged $inputSmali $text "InputProcessor strafeDir field"
}

# --------------------------------------- InputProcessor: setStrafe/getStrafe
$text = Get-Content -LiteralPath $inputSmali -Raw
if ($text -notmatch '\.method public setStrafe\(F\)V') {
    $anchor = '.method public move(FI)Z'
    if ($text -notmatch [regex]::Escape($anchor)) { throw "anchor for setStrafe not found" }
    $methods = @'
.method public setStrafe(F)V
    .locals 0
    .param p1, "value"    # F

    .prologue
    iput p1, p0, Lcom/bendroid/questengine/logic/InputProcessor;->strafeDir:F
    return-void
.end method

.method public getStrafe()F
    .locals 1
    .prologue
    iget v0, p0, Lcom/bendroid/questengine/logic/InputProcessor;->strafeDir:F
    return v0
.end method

'@
    $text = $text -replace ([regex]::Escape($anchor)), ($methods + $anchor)
    Save-IfChanged $inputSmali $text "InputProcessor setStrafe/getStrafe"
}

# ------------------------------------------- InputProcessor: strafe in move
$text = Get-Content -LiteralPath $inputSmali -Raw
if ($text -notmatch ':skip_strafe') {
    $pattern = '(invoke-direct \{p0, v0, v1\}, Lcom/bendroid/questengine/logic/InputProcessor;->moveCamera\([^)]*\)V\r?\n)(\s*\.line \d+\r?\n)(\s*const/4 v0, 0x1\r?\n\r?\n\s*return v0\r?\n\.end method)'
    $replacement = @'
$1$2    iget v5, p0, Lcom/bendroid/questengine/logic/InputProcessor;->strafeDir:F

    const/4 v4, 0x0

    cmpl-float v4, v5, v4

    if-eqz v4, :skip_strafe

    invoke-virtual {p0, v5, p2}, Lcom/bendroid/questengine/logic/InputProcessor;->strafe(FI)V

    :skip_strafe
$3
'@
    $new = [regex]::Replace($text, $pattern, $replacement, 1)
    if ($new -eq $text) { throw "move() anchor not found" }
    Save-IfChanged $inputSmali $new "InputProcessor move() applies strafe"
}

# ------------------------------------------- InputProcessor: the strafe method
# move() calls strafe() for the sideways component, so the method has to exist.
$text = Get-Content -LiteralPath $inputSmali -Raw
if ($text -notmatch '\.method public strafe\(FI\)V') {
    $anchor = '.method public processDown(Landroid/view/MotionEvent;)V'
    if ($text -notmatch [regex]::Escape($anchor)) { throw "anchor for strafe() not found" }
    $method = @'
.method public strafe(FI)V
    .locals 6
    .param p1, "direction"    # F
    .param p2, "delta"    # I

    .prologue
    const/high16 v5, 0x447a0000    # 1000.0f

    const/high16 v4, 0x43c80000    # 400.0f

    iget-object v0, p0, Lcom/bendroid/questengine/logic/InputProcessor;->plus:Lcom/bendroid/global/math/Point3D;

    iget-object v1, p0, Lcom/bendroid/questengine/logic/InputProcessor;->logic:Lcom/bendroid/questengine/logic/QuestLogic;

    invoke-virtual {v1}, Lcom/bendroid/questengine/logic/QuestLogic;->getCamera()Lcom/bendroid/global/objects/Camera;

    move-result-object v1

    invoke-virtual {v1}, Lcom/bendroid/global/objects/Camera;->getLookDirection()Lcom/bendroid/global/math/Point3D;

    move-result-object v1

    iget v1, v1, Lcom/bendroid/global/math/Point3D;->z:F

    neg-float v1, v1

    const/4 v2, 0x0

    iget-object v3, p0, Lcom/bendroid/questengine/logic/InputProcessor;->logic:Lcom/bendroid/questengine/logic/QuestLogic;

    invoke-virtual {v3}, Lcom/bendroid/questengine/logic/QuestLogic;->getCamera()Lcom/bendroid/global/objects/Camera;

    move-result-object v3

    invoke-virtual {v3}, Lcom/bendroid/global/objects/Camera;->getLookDirection()Lcom/bendroid/global/math/Point3D;

    move-result-object v3

    iget v3, v3, Lcom/bendroid/global/math/Point3D;->x:F

    invoke-virtual {v0, v1, v2, v3}, Lcom/bendroid/global/math/Point3D;->setPoints(FFF)V

    iget-object v0, p0, Lcom/bendroid/questengine/logic/InputProcessor;->plus:Lcom/bendroid/global/math/Point3D;

    invoke-virtual {v0}, Lcom/bendroid/global/math/Point3D;->normalize()Lcom/bendroid/global/math/Point3D;

    iget-object v0, p0, Lcom/bendroid/questengine/logic/InputProcessor;->plus:Lcom/bendroid/global/math/Point3D;

    iget v1, v0, Lcom/bendroid/global/math/Point3D;->x:F

    int-to-float v2, p2

    mul-float/2addr v2, p1

    div-float/2addr v2, v5

    mul-float/2addr v2, v4

    mul-float/2addr v1, v2

    iput v1, v0, Lcom/bendroid/global/math/Point3D;->x:F

    iget-object v0, p0, Lcom/bendroid/questengine/logic/InputProcessor;->plus:Lcom/bendroid/global/math/Point3D;

    iget v1, v0, Lcom/bendroid/global/math/Point3D;->z:F

    int-to-float v2, p2

    mul-float/2addr v2, p1

    div-float/2addr v2, v5

    mul-float/2addr v2, v4

    mul-float/2addr v1, v2

    iput v1, v0, Lcom/bendroid/global/math/Point3D;->z:F

    iget-object v0, p0, Lcom/bendroid/questengine/logic/InputProcessor;->logic:Lcom/bendroid/questengine/logic/QuestLogic;

    invoke-virtual {v0}, Lcom/bendroid/questengine/logic/QuestLogic;->getCamera()Lcom/bendroid/global/objects/Camera;

    move-result-object v0

    iget-object v1, p0, Lcom/bendroid/questengine/logic/InputProcessor;->plus:Lcom/bendroid/global/math/Point3D;

    invoke-direct {p0, v0, v1}, Lcom/bendroid/questengine/logic/InputProcessor;->moveCamera(Lcom/bendroid/global/objects/Camera;Lcom/bendroid/global/math/Point3D;)V

    return-void
.end method

'@
    $text = $text.Replace($anchor, $method + $anchor)
    Save-IfChanged $inputSmali $text "InputProcessor strafe() method"
}

# -------------------------------------- InputProcessor: the back key shows the bag
# The original back key opened the exit/menu dialog (confirmExit or showMenu).
# It now toggles the inventory, the same thing the bag button and the menu key
# do, so the game is not left by accident while the controls are used.
$text = Get-Content -LiteralPath $inputSmali -Raw
if ($text -match 'QuestEngine;->isInMenu\(\)Z') {
    $pattern = '(?s)    invoke-virtual \{v0\}, Lcom/bendroid/questengine/logic/QuestLogic;->getContext\(\)Lcom/bendroid/questengine/QuestEngine;\r?\n\r?\n    move-result-object v0\r?\n\r?\n    invoke-virtual \{v0\}, Lcom/bendroid/questengine/QuestEngine;->isInMenu\(\)Z.*?->showMenu\(\)V\r?\n\r?\n    goto :goto_1'
    $replacement = @'
    invoke-virtual {v0}, Lcom/bendroid/questengine/logic/QuestLogic;->getInventory()Lcom/bendroid/questengine/logic/inventory/Inventory;

    move-result-object v0

    invoke-virtual {v0}, Lcom/bendroid/questengine/logic/inventory/Inventory;->toggleVisibility()V

    move v0, v2

    goto :goto_0
'@
    $new = [regex]::Replace($text, $pattern, $replacement, 1)
    if ($new -eq $text) { throw "back key anchor not found" }
    Save-IfChanged $inputSmali $new "InputProcessor back key toggles the inventory"
}

# ------------------------------------------------- QuestEngine: install views
$text = Get-Content -LiteralPath $engineSmali -Raw
if ($text -notmatch 'ModViews;->install') {
    $pattern = '(invoke-virtual \{p0, v1\}, Lcom/bendroid/questengine/QuestEngine;->setContentView\(I\)V)'
    $replacement = @'
$1

    invoke-static {p0}, Lcom/bendroid/questengine/logic/ModViews;->install(Lcom/bendroid/questengine/QuestEngine;)V
'@
    $new = [regex]::Replace($text, $pattern, $replacement, 1)
    if ($new -eq $text) { throw "setContentView anchor not found" }
    Save-IfChanged $engineSmali $new "QuestEngine onCreate installs the controls"
}

# ------------------------------------- QuestEngine: showForward/hideForward
$text = Get-Content -LiteralPath $engineSmali -Raw
foreach ($pair in @(@('hideForward', '0x0'), @('showForward', '0x1'))) {
    $name = $pair[0]
    $value = $pair[1]
    $start = $text.IndexOf(".method public $name()V")
    if ($start -lt 0) { throw "$name not found" }
    $end = $text.IndexOf(".end method", $start)
    if ($end -lt 0) { throw "end of $name not found" }
    $end = $end + ".end method".Length
    $replacement = @"
.method public $name()V
    .locals 1

    .prologue
    const/4 v0, $value

    invoke-static {p0, v0}, Lcom/bendroid/questengine/logic/ModViews;->setWalkControlsVisible(Lcom/bendroid/questengine/QuestEngine;Z)V

    return-void
.end method
"@
    $text = $text.Substring(0, $start) + $replacement + $text.Substring($end)
    Write-Host "  QuestEngine $name : patched"
}
Save-IfChanged $engineSmali $text "QuestEngine showForward/hideForward"

# ------------------------------- InputProcessor: the camera must rotate
# The original dex jumps to the end of processMove when the invert option is
# off, so the camera could only be turned with the invert option turned on,
# and then it turned the wrong way. The factor is now simply minus one when
# the option is on and plus one when it is off.
$text = Get-Content -LiteralPath $inputSmali -Raw
if ($text -notmatch ':not_inverted_') {
    # the label numbers depend on the whole method (they are not the same on a
    # fresh decode), so both the cond_ and the goto_ label are captured and
    # only the cond_ one is renamed
    $pattern = 'if-eqz v4, :cond_(\d+)(\r?\n)\2    move v4, v6\2\2    :goto_(\d+)'
    $script:invCounter = 0
    $evaluator = {
        param($m)
        $script:invCounter++
        $n = $script:invCounter
        $eol = $m.Groups[2].Value
        $lbl = $m.Groups[3].Value
        return "if-eqz v4, :not_inverted_$n$eol$eol    move v4, v6$eol$eol    goto :goto_$lbl$eol$eol    :not_inverted_$n$eol    const/4 v4, 0x1$eol$eol    :goto_$lbl"
    }
    $new = [regex]::Replace($text, $pattern, $evaluator)
    if ($new -eq $text) { throw "invert anchor not found" }
    Save-IfChanged $inputSmali $new "InputProcessor camera rotation"
}

# ------------------------- InputProcessor: the now unused branch targets go away
# The rotation above replaced the branches to :cond_1 and :cond_2 with its own
# labels, so the block at the end of processMove that defines them is dead. It
# is removed, otherwise the verifier complains about unreachable code.
$text = Get-Content -LiteralPath $inputSmali -Raw
if ($text -match '\.restart local v0    # "angleXY":F') {
    $pattern = '(?s)    return-void\r?\n\r?\n    \.restart local v0    # "angleXY":F.*?goto :goto_1'
    $new = [regex]::Replace($text, $pattern, '    return-void', 1)
    if ($new -eq $text) { throw "processMove dead code anchor not found" }
    Save-IfChanged $inputSmali $new "InputProcessor processMove dead code removed"
}

# ----------------------------- the animation length limits are removed again
# The mod used to cut animations short: twelve seconds for the b3a animations
# (MaxAnimationLine) and 11.5 seconds for the xml ones (AnimationLine), because
# the game stays in the busy state and does not react to touches while an
# animation runs. Cutting them short breaks every scene that is longer than the
# limit: the animation is stopped in the middle, the camera and the objects stay
# where they were at that moment and the game continues from a half finished
# state (the player ends up in a broken, empty looking world). Both limits are
# removed here, the cutscenes run exactly as long as in the original apk again.
$maxSmali = "$Project\smali\com\bendroid\global\animations\max\MaxAnimationLine.smali"
if (Test-Path -LiteralPath $maxSmali) {
    $text = Get-Content -LiteralPath $maxSmali -Raw
    if ($text -match ':anim_cap_ok') {
        # the whole block, up to and including the label line, the branch target
        # inside the block is not the label so the match can not stop there
        $text = [regex]::Replace($text, '(?s)\r?\n[ \t]*# an animation may not block the game.*?\r?\n[ \t]*:anim_cap_ok', '')
        Save-IfChanged $maxSmali $text "MaxAnimationLine length limit removed"
    }
}

$simpleAnim = "$Project\smali\com\bendroid\global\animations\simple\AnimationLine.smali"
if (Test-Path -LiteralPath $simpleAnim) {
    $text = Get-Content -LiteralPath $simpleAnim -Raw
    if ($text -match ':anim_time_ok') {
        # the code of the original method continues on the same line as the
        # label, the branch target inside the block is not the label so the
        # match can not stop there
        $text = [regex]::Replace($text, '(?s)(\.prologue\r?\n)[ \t]*# an animation may not block the game.*?\r?\n[ \t]*:anim_time_ok', '$1')
        $text = [regex]::Replace($text, '\r?\n\.field private animTime:I', '')
        Save-IfChanged $simpleAnim $text "AnimationLine length limit removed"
    }
}

# ------------------------------- the intro of chapter three is played again
# The intro used to be jumped to its last frame so that the player does not have
# to watch it. It is a real cutscene and has to play, the state of the world is
# built by it frame by frame.
$logicSmali = "$Project\smali\com\bendroid\questengine\logic\QuestLogic.smali"
if (Test-Path -LiteralPath $logicSmali) {
    $text = Get-Content -LiteralPath $logicSmali -Raw
    if ($text -match '# mod: intro skipped') {
        $text = [regex]::Replace($text, '(?s)\r?\n[ \t]*# mod: intro skipped.*?invoke-virtual \{v0, v1\}, Lcom/bendroid/global/animations/IAnimationLine;->animate\(I\)Z', '')
        Save-IfChanged $logicSmali $text "QuestLogic plays the intro again"
    }
}

# ------------------------------------- LogicXMLHandler: inventory on the ui
# The game loads its logic on the gl thread and adds the item views to the
# inventory while it does so. Touching views from a thread other than the one
# that created them is not allowed on current android versions, the game dies
# with a CalledFromWrongThreadException. The item is handed over to the ui
# thread instead.
$xmlHandler = "$Project\smali\com\bendroid\global\loaders\LogicXMLHandler.smali"
if (Test-Path -LiteralPath $xmlHandler) {
    $text = Get-Content -LiteralPath $xmlHandler -Raw
    $old = 'invoke-virtual {v0, v1}, Lcom/bendroid/questengine/logic/inventory/Inventory;->addInventoryItem(Lcom/bendroid/questengine/logic/inventory/InventoryItem;)V'
    $new = 'invoke-static {v0, v1}, Lcom/bendroid/questengine/logic/ModInventory;->addItem(Lcom/bendroid/questengine/logic/inventory/Inventory;Lcom/bendroid/questengine/logic/inventory/InventoryItem;)V'
    if ($text.Contains($old)) {
        $text = $text.Replace($old, $new)
        Save-IfChanged $xmlHandler $text "LogicXMLHandler inventory on the ui thread"
    }
}

# ------------------------------- BaseLogicRoutine: decode textures safely
$baseSmali = "$Project\smali\com\bendroid\global\logic\BaseLogicRoutine.smali"
if (-not (Test-Path -LiteralPath $baseSmali)) { throw "missing $baseSmali" }
$text = Get-Content -LiteralPath $baseSmali -Raw
if ($text -notmatch 'TextureFix;->load') {
    # the decode call, both with and without options
    $old = 'invoke-static \{([^}]*)\}, Landroid/graphics/BitmapFactory;->decodeResource\(Landroid/content/res/Resources;I\)Landroid/graphics/Bitmap;'
    $new = 'invoke-static {$1}, Lcom/bendroid/questengine/logic/TextureFix;->load(Landroid/content/res/Resources;I)Landroid/graphics/Bitmap;'
    $text = [regex]::Replace($text, $old, $new, 1)
    $old = 'invoke-static \{([^}]*)\}, Landroid/graphics/BitmapFactory;->decodeResource\(Landroid/content/res/Resources;ILandroid/graphics/BitmapFactory\$Options;\)Landroid/graphics/Bitmap;'
    $new = 'invoke-static {$1}, Lcom/bendroid/questengine/logic/TextureFix;->load(Landroid/content/res/Resources;ILandroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;'
    $text = [regex]::Replace($text, $old, $new, 1)
    if ($text -notmatch 'TextureFix;->load') { throw "decodeResource anchor not found" }

    # upload the bitmap with the overload that reads the format from the bitmap
    # the registers are (gl, level, internalFormat, bitmap, border) and become
    # (gl, level, bitmap, border)
    $oldUpload = 'invoke-static \{([^,]+), ([^,]+), [^,]+, ([^,]+), ([^,]+)\}, Landroid/opengl/GLUtils;->texImage2D\(IIILandroid/graphics/Bitmap;I\)V'
    $newUpload = 'invoke-static {$1, $2, $3, $4}, Landroid/opengl/GLUtils;->texImage2D(IILandroid/graphics/Bitmap;I)V'
    $text = [regex]::Replace($text, $oldUpload, $newUpload, 1)
    if ($text -notmatch 'texImage2D\(IILandroid/graphics/Bitmap;I\)V') { throw "texImage2D anchor not found" }
    Save-IfChanged $baseSmali $text "BaseLogicRoutine texture loading"
}

# ------------------ InventoryItem: this must not be used before super()
# Chapter three passes the not yet initialized this reference to a static
# method before the constructor of ImageView is called. The verifier of
# current android versions rejects that.
$itemSmali = "$Project\smali\com\bendroid\questengine\logic\inventory\InventoryItem.smali"
if (Test-Path -LiteralPath $itemSmali) {
    $text = Get-Content -LiteralPath $itemSmali -Raw
    $old = 'invoke-static {p0, v1}, Lcom/bendroid/questengine/logic/inventory/InventoryItem;->dpToPx(Landroid/content/Context;I)I'
    $new = 'invoke-static {p1, v1}, Lcom/bendroid/questengine/logic/inventory/InventoryItem;->dpToPx(Landroid/content/Context;I)I'
    if ($text.Contains($old)) {
        $text = $text.Replace($old, $new)
        Save-IfChanged $itemSmali $text "InventoryItem constructor"
    }
}

# ------------------------------- the activity is put into immersive fullscreen
# The window decor view is switched to sticky immersive mode, so the game uses
# the whole screen and the bars do not come back when the player touches the
# edges. The block is inserted right after super.onCreate, before the content
# is used.
$activityFile = @(Get-ChildItem -Path "$Project\smali\com\bendroid" -Recurse -Filter "Mystique*Activity.smali" -ErrorAction SilentlyContinue) | Select-Object -First 1
if ($activityFile) {
    $text = Get-Content -LiteralPath $activityFile.FullName -Raw
    if ($text -notmatch 'SYSTEM_UI_FLAG_IMMERSIVE_STICKY') {
        $block = @'
    invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
    move-result-object v0

    invoke-virtual {v0}, Landroid/view/Window;->getDecorView()Landroid/view/View;
    move-result-object v0

    sget v1, Landroid/view/View;->SYSTEM_UI_FLAG_IMMERSIVE_STICKY:I
    sget v2, Landroid/view/View;->SYSTEM_UI_FLAG_LAYOUT_STABLE:I
    or-int/2addr v1, v2
    sget v2, Landroid/view/View;->SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN:I
    or-int/2addr v1, v2
    sget v2, Landroid/view/View;->SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION:I
    or-int/2addr v1, v2
    sget v2, Landroid/view/View;->SYSTEM_UI_FLAG_FULLSCREEN:I
    or-int/2addr v1, v2
    sget v2, Landroid/view/View;->SYSTEM_UI_FLAG_HIDE_NAVIGATION:I
    or-int/2addr v1, v2

    invoke-virtual {v0, v1}, Landroid/view/View;->setSystemUiVisibility(I)V

'@
        $start = $text.IndexOf(".method public onCreate(Landroid/os/Bundle;)V")
        if ($start -lt 0) { throw "onCreate not found in $($activityFile.Name)" }
        $end = $text.IndexOf(".end method", $start)
        $method = $text.Substring($start, $end - $start)
        $super = $method.IndexOf("->onCreate(Landroid/os/Bundle;)V")
        if ($super -lt 0) { throw "super.onCreate not found in $($activityFile.Name)" }
        # insert after the .line marker that follows the super call
        $line = [regex]::Match($method.Substring($super), '[\r\n]+\s*\.line \d+[\r\n]+')
        if (-not $line.Success) { throw "line marker not found in $($activityFile.Name)" }
        $method = $method.Insert($super + $line.Index + $line.Length, $block)
        $locals = [regex]::Match($method, '\.locals (\d+)')
        if ($locals.Success -and [int]$locals.Groups[1].Value -lt 3) {
            $method = $method.Remove($locals.Index, $locals.Length).Insert($locals.Index, '.locals 3')
        }
        $text = $text.Substring(0, $start) + $method + $text.Substring($end)
        Save-IfChanged $activityFile.FullName $text "$($activityFile.Name) immersive fullscreen"
    }
} else {
    throw "no Mystique activity found in $Project\smali\com\bendroid"
}

# ------------------------------------------------ AndroidManifest: the basics
# largeHeap gives the game enough memory for its textures, the screen settings
# let it rotate and resize, and the launcher activity has to declare exported
# on current android versions. The original apks of these chapters also carry
# the activity configuration of 2015, which is updated here.
$manifestPath = "$Project\AndroidManifest.xml"
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
    $manifest = [regex]::Replace($manifest, '(android:name="\.Mystique\d+Activity"[^>]*?)>', '$1 android:resizeableActivity="true" android:exported="true">', 1)
    Save-IfChanged $manifestPath $manifest "AndroidManifest"
}

# -------------------------------------- InventoryItem: dp instead of pixels
# The item size was a fixed pixel value, so the inventory items were tiny on a
# screen with a high density. The constructor now converts it from dp.
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
    $text = $text.TrimEnd() + "`r`n`r`n" + $method.TrimStart() + "`r`n"
    Save-IfChanged $itemSmali $text "InventoryItem dp size"
}

Write-Host "patched $Project" -ForegroundColor Green

