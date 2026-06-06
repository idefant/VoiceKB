# VoiceKB Product Direction

## Product Summary

VoiceKB is an Android voice-input keyboard. It records speech until the user
explicitly finishes or cancels the recording, transcribes the audio, and
inserts the resulting text into the active input field.

The product should feel like a focused system keyboard utility: fast to open,
quiet visually, and predictable during repeated daily use.

## Current Redesign Goal

Replace the old keyboard UI with the new design in `voicekb-disign.pen`
without changing the Android stack and without removing existing features.

The redesign is being implemented in logical stages:

1. Keyboard surface and header.
2. Recording, paused, sending, and idle states.
3. Light and dark themes.
4. Quick settings and language selection dialogs.
5. Long-press special-character tray.
6. Browser `ACTION_RECOGNIZE_SPEECH` voice input.
7. Broader settings and onboarding polish.

The current implementation focus is the keyboard itself. Secondary screens
should not distract from matching the Pencil keyboard design and stabilizing
IME behavior.

## Accepted UX Decisions

- VoiceKB does not stop automatically because of silence.
- The main recording button keeps a fixed size between states.
- Idle: blue microphone button, no timer, reset and pause controls hidden.
- Recording: red send button with timer, reset and pause controls visible.
- Paused: blue send button with timer, reset and resume controls visible.
- Sending: spinner instead of the main icon, no timer, pause hidden, reset
  visible, resend disabled.
- Hidden controls keep their layout space so the keyboard does not jump.
- The space bar shows left and right arrows to communicate cursor movement.
- Header icon-only buttons are circular and use a `32dp` diameter.
- The header language chip shows a short active language label or `DETECT`.
- A header `clock-4` button directly controls whether VoiceKB returns to the
  previous keyboard after successful insertion. Its value persists.
- Optional file transcription uses a dedicated `file-volume` button. Selecting
  a file immediately starts transcription; the recording button has no hidden
  long-press upload action.
- Quick settings and language selection appear above the keyboard as attached
  dialogs. The IME remains visible when they close.
- Quick settings contain keyboard settings, language selection, change input
  method, and cancel. The return toggle is intentionally kept in the header.
- Returning to the previous keyboard after text insertion is optional. When
  disabled, VoiceKB stays open.
- Browser voice input uses a compact centered dialog with the same recording
  states and the same configured language list as the IME. Opening language
  selection pauses an active recording until the user explicitly resumes it.
- Light and dark themes must match the Pencil design rather than approximate
  colors.

## Product Constraints

- Keep the existing Java and Android XML implementation.
- Avoid unnecessary work during IME startup. Keyboard switching should feel
  immediate.
- Preserve all existing features unless removal is explicitly requested.
- Use system-like Android interaction patterns where practical.
- Test changes on the emulator in Markor `QuickNote`.

## Deferred Decisions

- Firebase Crashlytics and Google Services are not active in the build. Runtime
  crash reporting remains deferred until privacy, opt-in behavior, or an
  open-source/self-hosted replacement is explicitly chosen.

## Updating This Document

Update this file when a UX decision changes, a redesign stage is completed, or
the implementation focus moves to another area of the app.
