# VoiceKB Keyboard Behavior

This document is the source of truth for VoiceKB IME behavior. Read and update
it before changing `VoiceKBInputMethodService`, keyboard lifecycle logic,
recording state transitions, temporary mode, quick settings, language selection,
file transcription, or keyboard switching.

## Core Principle

VoiceKB is a voice-input keyboard, not a general text keyboard. It should open
quickly, record until the user explicitly finishes or cancels, insert text
predictably, and avoid surprising keyboard switches.

The Android IME lifecycle is noisy. `onStartInputView()` and
`onFinishInputView()` can fire when the user switches keyboards, changes input
fields, opens Recents, locks/unlocks the phone, returns from settings, opens an
attached dialog, or returns from a file picker. Do not treat those callbacks
alone as proof that the user intentionally started or ended a VoiceKB session.

## Recording States

- Idle: recording is not active; the main button shows the microphone.
- Preparing: VoiceKB is getting ready to record, for example while waiting for
  Bluetooth SCO.
- Recording: audio is being captured; the main button is red and shows send
  with a timer.
- Paused: recording is paused; the main button is blue and shows send with the
  existing duration.
- Sending: audio is being transcribed; the main button shows a spinner and no
  timer.

Hidden controls should reserve their layout space unless the user explicitly
asked for a layout change. Button geometry should remain stable across states
and touch effects.

## Instant Recording

Instant recording is meant for this workflow:

1. The user is using another keyboard.
2. The user taps that keyboard's microphone/input-method action and switches to
   VoiceKB.
3. VoiceKB starts recording immediately so the user does not have to tap the
   microphone twice.

Instant recording must not start merely because Android recreated the VoiceKB
input view. In particular, it must not start after:

- opening Recents and returning to the original app;
- locking/unlocking the phone;
- returning from VoiceKB settings;
- returning from quick settings or language selection;
- returning from file picker;
- changing focus between input fields while VoiceKB is already selected.

Implementation guidance: use an explicit latch for "instant recording already
consumed for the current VoiceKB IME selection". Reset that latch only when
VoiceKB is no longer the selected input method or when VoiceKB explicitly
switches away from itself.

## Temporary Mode

Temporary mode is controlled by
`com.idefant.voicekb.return_to_previous_keyboard`.

When enabled, VoiceKB should return to the previous input method after a
successful ordinary transcription insertion. This mirrors the common voice input
workflow: speak, insert text, return to the user's normal keyboard.

When disabled, VoiceKB should remain visible after inserting text. The keyboard
should not disappear or switch away automatically.

Temporary mode should not force a return to the previous keyboard for internal
VoiceKB flows:

- opening full settings from quick settings;
- opening language selection;
- changing settings and returning;
- opening file picker;
- selecting a file and sending it for transcription.

File transcription is an explicit VoiceKB action. After file transcription
finishes, VoiceKB should stay open even when temporary mode is enabled.

## Recents, Home, Lock, And Sleep

Switching input methods from `onFinishInputView()` during Recents/Home/sleep is
not reliable. Android may accept the request, delay it, or drop it because the
target app is already losing focus. This causes inconsistent behavior: sometimes
the previous keyboard returns, sometimes VoiceKB remains selected.

Preferred stable rule:

- Do not switch keyboards only because the user opened Recents, pressed Home, or
  locked the phone.
- Return to the previous keyboard after successful text insertion when
  temporary mode is enabled.
- Return to the previous keyboard when the user presses the explicit keyboard
  switch button.
- Preserve VoiceKB during internal actions such as settings and file picker.

If the product direction changes and we intentionally want Google Voice
Input-like cancellation on Recents/Home/lock, implement it with an explicit,
testable lifecycle signal rather than relying only on `onFinishInputView()`.

## File Transcription

File transcription uses the dedicated `file-volume` button. The recording button
must not have a hidden long-press upload action.

When the user chooses file transcription while recording or paused, VoiceKB
should cancel the current recording first, then open the file picker. After the
file is selected, VoiceKB sends it for transcription immediately.

The file transcription button should be hidden only when the feature is disabled
in settings. If the feature is enabled but the current action is temporarily
unavailable, use a disabled visual state instead of moving surrounding controls.

## Resend

Resend sends the last valid audio file. Deleting/resetting the current recording
must not make the just-deleted fragment become the resend target.

If resend is enabled but there is no valid audio to resend, keep the button in
place and disabled. Do not hide it, because hiding it makes the keyboard layout
jump.

When resend is pressed during recording or pause, VoiceKB should cancel the
current recording first, then send the previous valid audio.

## Quick Settings And Language Selection

Quick settings and language selection are attached dialogs above the IME, not
panels embedded inside the keyboard layout. Closing them must not hide VoiceKB or
start/stop/send a recording by itself.

Moving from quick settings to language selection should close the first attached
dialog and open the second without losing the IME.

Opening full settings from quick settings should not trigger temporary-mode
return. The user is expected to come back to VoiceKB.

## Special Characters Tray

Long-pressing Enter opens the special-character tray. While the finger stays
inside the tray selection area, the horizontal space is divided into slots for
visible characters. Moving through gaps between character buttons should keep a
nearest character selected instead of clearing selection.

Selection is cancelled only when the finger leaves the overall tray selection
area. The active area starts at the tray's left and top edges and extends to the
right and bottom edges of the keyboard, so moving below or to the right of the
tray keeps the nearest character selected. Moving left of the tray or above it
cancels selection. Releasing with a selected character inserts it; releasing
after selection was cancelled closes the tray without inserting anything.

## Browser Voice Input

Browser `ACTION_RECOGNIZE_SPEECH` uses a separate compact dialog flow. Smart IME
insertion rules do not apply there, because browser voice input is generally
used for search or one-shot recognition.

Browser voice input may use related visual states, but it is not the same
lifecycle as `VoiceKBInputMethodService`.

## Smart Insertion

Smart insertion applies only to ordinary IME insertion through VoiceKB. It should
read a small amount of text before the cursor and adjust only the first letter of
the inserted text according to the user's smart insertion settings.

Do not apply smart insertion to browser voice input.

## User-Facing Messages

Do not show unsolicited runtime popups for rating, donation, support, changelog,
or "what's new" in the keyboard. User-facing messages shown inside the keyboard
should be actionable and directly related to the user's current action, such as
API errors, missing configuration, unsupported file format, or permission issues.

## Testing Checklist

When changing keyboard behavior, test at least:

- switching from another IME to VoiceKB with instant recording enabled;
- Recents and return to the original app;
- lock/unlock while VoiceKB is selected;
- opening full settings and returning;
- quick settings open/close;
- language selection open/close;
- file picker open/cancel/select;
- temporary mode enabled and disabled;
- resend with and without a valid previous audio file;
- recording, paused, sending, reset, and idle states.

Run:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
git diff --check
```

Install to the physical Android device only when the user asks for it. Do not
use audio/sound notifications for automatic checks; reserve sounds for moments
when the user needs to physically come back, approve, or review something.

## Updating This Document

Update this file whenever keyboard lifecycle behavior changes. If a requested
change conflicts with this document, point out the conflict before implementing
it.
