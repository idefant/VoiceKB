# VoiceKB Development Runbook

## Local Environment

Expected Windows tools:

- Android SDK: `%LOCALAPPDATA%\Android\Sdk`
- ADB: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- Emulator: `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe`
- Android Studio: `C:\Program Files\Android\Android Studio\bin\studio64.exe`
- AVD: `Medium_Phone_API_36.0`

Expected emulator apps:

- Markor: `net.gsantner.markor`
- AnySoftKeyboard: `com.menny.android.anysoftkeyboard/.SoftKeyboard`
- VoiceKB release: `com.idefant.voicekb`
- VoiceKB debug: `com.idefant.voicekb.debug`

## Build

Run from the repository root:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
git diff --check
```

Debug APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Start The Emulator

The emulator writes lock files under `%USERPROFILE%\.android\avd`. When Codex
uses a filesystem sandbox, launch the GUI emulator with an approved host-level
command. A foreground sandboxed launch can fail repeatedly with access denied
errors for `snapshot.lock.lock`.

```powershell
Start-Process `
  -FilePath "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" `
  -ArgumentList '-avd','Medium_Phone_API_36.0','-no-snapshot-save'
```

Wait for Android:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
& $adb -s emulator-5554 shell getprop sys.boot_completed
```

Expected boot value:

```text
1
```

If the cold-boot screen is locked:

```powershell
& $adb -s emulator-5554 shell input keyevent 224
& $adb -s emulator-5554 shell wm dismiss-keyguard
& $adb -s emulator-5554 shell input swipe 540 1900 540 600 350
```

## Install VoiceKB

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 install -r `
  'app\build\outputs\apk\debug\app-debug.apk'
& $adb -s emulator-5554 shell ime enable `
  com.idefant.voicekb.debug/com.idefant.voicekb.core.VoiceKBInputMethodService
```

Switch to VoiceKB:

```powershell
& $adb -s emulator-5554 shell ime set `
  com.idefant.voicekb.debug/com.idefant.voicekb.core.VoiceKBInputMethodService
```

Restore the normal keyboard after testing:

```powershell
& $adb -s emulator-5554 shell ime enable `
  com.menny.android.anysoftkeyboard/.SoftKeyboard
& $adb -s emulator-5554 shell ime set `
  com.menny.android.anysoftkeyboard/.SoftKeyboard
```

## Open The Manual Test Screen

Markor is the primary text-entry test app. Use its `QuickNote` tab.

```powershell
& $adb -s emulator-5554 shell am force-stop net.gsantner.markor
& $adb -s emulator-5554 shell am start -n `
  net.gsantner.markor/.activity.MainActivity
```

Useful approximate coordinates for the current AVD:

```text
QuickNote tab:          575 2260
Editor body:            300 450
VoiceKB settings icon:  470 1755
```

Coordinates are helpers, not contracts. Re-check the UI hierarchy when a
layout change makes a scripted tap ambiguous.

## Inspect UI State

Confirm the active IME and whether Android considers it visible:

```powershell
& $adb -s emulator-5554 shell settings get secure default_input_method
& $adb -s emulator-5554 shell dumpsys input_method |
  Select-String 'mInputShown'
```

Capture a screenshot:

```powershell
& $adb -s emulator-5554 shell screencap -p `
  /data/local/tmp/voicekb-screen.png
& $adb -s emulator-5554 pull `
  /data/local/tmp/voicekb-screen.png `
  '.\voicekb-screen.png'
```

Dump the UI hierarchy:

```powershell
& $adb -s emulator-5554 shell uiautomator dump `
  /sdcard/voicekb-window.xml
& $adb -s emulator-5554 pull `
  /sdcard/voicekb-window.xml `
  '.\voicekb-window.xml'
```

Remove temporary screenshots and dumps after inspection so they do not remain
in the Git working tree.

## Theme Checks

Use Android UI mode commands for visual checks:

```powershell
& $adb -s emulator-5554 shell cmd uimode night yes
& $adb -s emulator-5554 shell cmd uimode night no
```

Restore light mode after testing unless the next task explicitly needs dark
mode.

## Core Manual Scenarios

For keyboard and IME lifecycle changes, verify:

1. Switch from AnySoftKeyboard to VoiceKB.
2. Record, finish, and insert text.
3. With return-to-previous enabled, AnySoftKeyboard becomes active.
4. With return-to-previous disabled, VoiceKB remains visible.
5. Open and cancel quick settings. VoiceKB remains visible.
6. Open quick settings, then language selection. The language dialog appears.
7. Cancel language selection. VoiceKB remains visible.
8. Repeat relevant checks in light and dark themes.

## Appium MCP

Appium MCP may be configured in the user's Codex environment. Treat it as an
optional convenience. If its tools are unavailable in a session, use ADB,
screenshots, `uiautomator`, and `dumpsys`; these are sufficient for the current
manual verification flow.
