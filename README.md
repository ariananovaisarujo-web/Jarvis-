# J.A.R.V.I.S. for Android

Iron-Man style voice assistant. Gemini is the brain, your phone is the body.

## Features (v1)
- Animated arc-reactor HUD (colour and motion follow listening / thinking / speaking)
- Talk by voice or type; Hindi / Hinglish / English
- Wake word: say "Jarvis ..." with the screen off (foreground service)
- Continuous conversation, say "bas" / "stop" / "thanks Jarvis" to end
- Deep British voice (or your own ElevenLabs voice: key + voice ID in Settings, falls back to phone voice on error); Hindi voice for Devanagari replies
- Phone control via Gemini function calling: open any app, call, SMS, WhatsApp draft,
  alarms, timers, flashlight, play music, media keys, volume, navigation, Google search,
  open links, weather, battery, time, open settings pages, camera, email draft
- Long-term memory ("remember that I like ...")

## Build the APK (GitHub Actions)
1. Create a new GitHub repo (public = unlimited free build minutes) and push this folder.
2. Open the Actions tab, run "Build Jarvis APK" (it also runs on every push).
3. Download `Jarvis-APK` from the run's Artifacts, or from the Releases page.
4. If the build fails, the errors are shown in the run's Summary.

## First run
1. Install the APK, open Jarvis, allow the permissions.
2. Settings: paste a free Gemini API key (button opens aistudio.google.com/apikey).
3. Optional: Settings, turn on the wake word, allow "display over other apps"
   (needed so Android lets Jarvis open apps from the background) and disable battery optimisation.

## Known limits (honest list)
- Needs internet (Gemini + Google speech recognition).
- Wake word uses Google's recogniser in a loop: some phones beep, and it can pause music while listening.
- Android does not let apps toggle Wi-Fi / Bluetooth / mobile data directly; Jarvis opens the right settings page.
- Reading screen, tapping other apps, reading notifications, camera vision are NOT in v1
  (planned: AccessibilityService, NotificationListener, camera + Gemini vision).
