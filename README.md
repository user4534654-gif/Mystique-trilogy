# Mystique — movement + inventory mod

Adds comfortable movement to the whole Mystique series and the missing
inventory button, so the games can actually be finished on a current phone.

This repository contains **only the mod's own code and scripts**. It does not
contain the games, their code, their resources or any apk. You have to own the
games and supply their stock apks yourself; the build scripts patch those.

| chapter | package | stock apk | mod version |
| --- | --- | --- | --- |
| 1 Foetus | `com.bendroid.mystique1` | `mystique-chapter-1-foetus-1-4.apk` | 1.4.1 (25) |
| 2 The Ghost | `com.bendroid.mystique2unlocked` | `mystiquech_2.apk` | 1.1.4 (15) |
| 3 | `com.bendroid.mystique3` | `mystiquech_3.apk` | 1.0.5 (6) |

## What it does

### Movement

* **virtual analog stick** in the bottom left corner (one finger, all 8 directions)
* **diagonal movement** works, the stick is camera relative
* **analog speed** — the further the knob is pushed, the faster the player walks

## Logging (chapters two and three)

The mod writes the state of the running game to logcat under the tag
`MYSTIQUE`: current state, animation and its progress, location, node stack,
camera, inventory, triggers, and a heartbeat once a second. The first lines
identify the build:

```
MYSTIQUE  BUILD version=1.0.5 versionCode=6 apk=base.apk sha256=...
MYSTIQUE  BUILD device=Google sdk_gphone64_x86_64 android=15 sdk=35 abi=x86_64
```

Capture only the game's lines:

```cmd
cmd /c "adb logcat -v time -s MYSTIQUE:I AndroidRuntime:E > C:\path\mystique-log.txt"
```

(`adb logcat -f` writes on the device, not on the host — use the redirect.)

## Building from your own apk

Requirements:

* JDK 11 or newer (`javac`, `jar`)
* Android SDK build-tools with `d8`, `zipalign`, `apksigner`
  (`%LOCALAPPDATA%\Android\Sdk\build-tools\...`)
* [apktool](https://apktool.org/) 3.0.3 as `apktool.jar` in the repo root
* the stock apk of the chapter you want to build
* a keystore. The released builds use a private key; for your own build use
  any key you like, for example:

  ```powershell
  keytool -genkeypair -keystore my.keystore -alias mystique -keyalg RSA -keysize 2048 -validity 10000 -storepass mystique -keypass mystique -dname "CN=Mystique"
  ```

Put the stock apks into `originals/` with the names from the table above (or
pass `-Original`), then:

```powershell
# chapter 1
powershell -ExecutionPolicy Bypass -File .\build-ch1.ps1

# chapter 2
powershell -ExecutionPolicy Bypass -File .\build-ch23.ps1 -Chapter 2

# chapter 3
powershell -ExecutionPolicy Bypass -File .\build-ch23.ps1 -Chapter 3
```

The scripts decode the stock apk, apply the patch, compile the mod classes to
dex, turn them back into smali and drop them into the apktool project, then
rebuild, zipalign and sign. Output goes to `dist/`.

Every stock apk is checked against `originals.sha256` first, so a build from a
wrong or modified file stops with an error instead of producing a different
apk. If your copy has another hash, it is not the build this mod was tested on.

To also run the reference check:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-ch23.ps1 -Chapter 3 -Verify
```

### Signing key

`mystique-mod.keystore` is **not** in this repository and must never be
published: anyone with it could sign an "update" that Android accepts for this
package. When you build yourself you sign with your own key, so the hash of
your apk differs from the released one and its signature is different. That is
expected.

The mod is signed with a key that differs from the original game, so a
previously installed build has to be uninstalled first (or the mod apk
uninstalled before going back to the stock one).

## Layout of the repository

```
src/                    chapter 1 mod classes (java)
stubs/                  compile time stubs for chapter 1
src-ch23/               chapters 2 and 3 mod classes (java)
stubs-ch23/             compile time stubs for chapters 2 and 3
tools/                  helper: store sounds uncompressed, px -> dp in compiled xml
patch-ch1.ps1           layout, smali and manifest changes of chapter 1
patch-ch23.ps1          smali and manifest changes of chapters 2 and 3
build-ch1.ps1           decode -> patch -> build -> sign, chapter 1
build-ch23.ps1          decode -> patch -> build -> sign, chapter 2 or 3
verify-apk.ps1          package/version/hash/mod marker of an apk
verify-references.ps1   checks that every game reference of the apk resolves
originals.sha256        hashes of the stock apks the mod was tested on
SHA256SUMS.txt          hashes of the released apks
```