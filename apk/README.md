# HushTube — Route 2 APK (Kotlin WebView wrapper)

Fullscreen WebView to the self-hosted Invidious (`https://<MACHINE>.<YOUR-TAILNET>.ts.net`
by default; LAN IP or any URL switchable in-app via the overflow menu →
"Change server", persisted in SharedPreferences). Sideload only — no Play Store.

## Features
- `domStorageEnabled`, `mediaPlaybackRequiresUserGesture=false`, zoom, black bg
- Fullscreen video via `WebChromeClient.onShowCustomView` + immersive system bars
- Back button: web history → exit fullscreen → exit app
- Download listener → system `DownloadManager` (Downloads dir)
- External links (non-server host, non-http schemes) handed off via `ACTION_VIEW`
- Tailscale-down error screen with **Open Tailscale** (`com.tailscale.ipn`) + Retry
- Cast button injected on `/watch` pages via `addDocumentStartJavaScript`
  (`assets/inject.js`); click → JS bridge `window.InvidiousCast.cast(id)` →
  native `POST <cast-bridge>/cast/play {"videoId":...,"mode":"youtube"}`.
  Bridge URL defaults to `http://<LAPTOP_LAN_IP>:8300` (the laptop's LAN IP — the phone
  can't resolve the bare hostname), editable via overflow menu → "Change cast
  bridge". Native POST (not fetch) on purpose: dodges https→http
  mixed-content blocking + CORS.
- Update check against hosted `version.json`
  (`{"versionCode":N,"versionName":"x.y","url":"apk-url"}` at
  `https://<MACHINE>.<YOUR-TAILNET>.ts.net/version.json`; failures silent)
- Release keystore from day one: `hushtube.keystore` + `keystore.properties`
  (both gitignored — **back them up; losing them means losing update identity**;
  rotated with the v1.25 HushTube rebrand — see [APK.md](../docs/APK.md))

Out of scope (by design): Play Store distribution, native Cast SDK (the cast
bridge is used instead), system PiP (the in-app mini player replaces it).
Background audio + MediaStyle notification shipped in v1.23–v1.24.

## Toolchain (all under $HOME, no sudo)
- Temurin JDK 21 → `~/tools/jdk21` (pinned via `org.gradle.java.home` in
  `gradle.properties`; system JDK 25 is too new for this AGP/Gradle pair)
- Gradle 8.14.7 → `~/tools/gradle-8.14.7` (wrapper generated from it)
- Android SDK → `~/Android` (cmdline-tools/latest, platform-tools,
  platforms 34+35, build-tools 34+35); `sdk.dir` in gitignored `local.properties`
- AGP 8.13.2, Kotlin 2.2.20, minSdk 26, target/compileSdk 35

## Build
```bash
cd ~/hushtube/apk
export ANDROID_HOME=$HOME/Android
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # signed with release keystore
```

## Release checklist
1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`.
2. `./gradlew assembleRelease`.
3. Host new APK + updated `version.json` where the update check points.

## Install on phone
`adb install -r app-debug.apk` or copy the APK and open it (allow sideloading).
