# VoiceKB

VoiceKB is an Android voice-input keyboard for people who want fast, deliberate
speech-to-text without silence timeouts. Switch to the keyboard, speak for as
long as you need, then send the recording and insert the transcript into the
current text field.

VoiceKB is inspired by [Dictate](https://github.com/DevEmperor/Dictate).

[Download the latest APK](https://github.com/idefant/VoiceKB/releases/latest)

## Screenshots

| Keyboard idle | Keyboard recording | Browser voice input | API settings |
| --- | --- | --- | --- |
| ![Keyboard idle](img/readme/keyboard-idle-light.png?raw=true) | ![Keyboard recording](img/readme/keyboard-recording-light.png?raw=true) | ![Browser voice input](img/readme/browser-voice-light.png?raw=true) | ![API settings](img/readme/api-settings-light.png?raw=true) |
| ![Keyboard idle](img/readme/keyboard-idle-dark.png?raw=true) | ![Keyboard recording](img/readme/keyboard-recording-dark.png?raw=true) | ![Browser voice input](img/readme/browser-voice-dark.png?raw=true) | ![API settings](img/readme/api-settings-dark.png?raw=true) |

## Features

- Continuous voice input: recording stops only when you finish or reset it.
- Provider choice: Groq, OpenAI, or a custom OpenAI-compatible endpoint.
- Browser voice input: a compact `ACTION_RECOGNIZE_SPEECH` popup for search fields.
- Language selection: choose Detect or one of the enabled input languages.
- Resend and file transcription: retry the last valid recording or transcribe an audio/video file.
- Smart insertion: optional capitalization rules based on text before the cursor.
- Quick settings: change language, open keyboard settings, or switch input method without leaving the IME.
- Light and dark keyboard themes designed for stable button geometry.

## Building

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

Release APKs are built by GitHub Actions when a tag like `v0.1.0` is pushed.
The release workflow expects Android signing secrets in the repository settings.

## License

VoiceKB is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
