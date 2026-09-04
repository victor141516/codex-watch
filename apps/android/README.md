# Android companion

The Android app is the watch's always-available relay: it reconnects over BLE, loads tasks from the desktop bridge, streams watch audio into a playable WAV recording, transcribes it after OpenAI sign-in, and sends the resulting text back to the selected Codex task.

Build from the repository root with `./scripts/build-android.ps1`.
