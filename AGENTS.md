# VoiceKB Development Notes

Read these notes before making changes. More detail lives in:

- `docs/PRODUCT_DIRECTION.md`
- `docs/DEVELOPMENT.md`
- `docs/KEYBOARD_BEHAVIOR.md`

## Project Shape

VoiceKB is an Android voice-input keyboard. Keep the existing stack:

- Java
- Android XML layouts and drawables
- Gradle wrapper from this repository

Do not migrate the UI to Compose or add a new UI framework unless the user
explicitly requests it.

The main keyboard service is:

- `app/src/main/java/com/idefant/voicekb/core/VoiceKBInputMethodService.java`

Keyboard lifecycle and behavior rules are documented in:

- `docs/KEYBOARD_BEHAVIOR.md`

The current Pencil design reference is:

- `voicekb-disign.pen`

## Current Direction

The old keyboard design is being replaced incrementally with the new Pencil
design. Preserve existing features unless the user explicitly asks to remove
one.

The current priority is the keyboard itself: layout, states, colors, touch
behavior, IME lifecycle, and emulator verification. Settings screens and
secondary panels should be changed only when the task requires them.

## Testing Expectations

Use the Android emulator for UI work. The expected local test setup is:

- AVD: `Medium_Phone_API_36.0`
- Text editor: Markor
- Markor tab: `QuickNote`
- Normal default IME: AnySoftKeyboard
- VoiceKB IME: `com.idefant.voicekb/.core.VoiceKBInputMethodService`

After UI testing, restore AnySoftKeyboard as the default IME unless the next
test immediately requires VoiceKB.

Run at least:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
git diff --check
```

For emulator commands, screenshots, installation, and manual scenarios, read
`docs/DEVELOPMENT.md`.

## Interaction Rules

- Do not remove existing behavior without an explicit request.
- Before changing IME lifecycle, temporary mode, instant recording, quick
  settings, language selection, file transcription, resend, or keyboard
  switching, read and update `docs/KEYBOARD_BEHAVIOR.md`.
- Keep button geometry stable during presses and state changes.
- Quick settings and language selection are attached dialogs above the IME.
  Closing them must not hide the keyboard.
- When the return-to-previous-keyboard preference is disabled, VoiceKB should
  remain visible after inserting text.
- Verify light and dark themes when changing keyboard colors or drawables.
- Treat `voicekb-disign.pen` as the source of truth for keyboard visuals.

## Machine-Specific State

Codex command approvals, Appium MCP configuration, Android SDK paths, and local
AVD files are user-machine state. Do not commit them to the repository.
