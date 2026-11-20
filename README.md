# Universal Player (Android starter project)

A deliberately minimal, offline-first Android video player starter using Kotlin and AndroidX Media3.

## Features in this starter
- Local video picker (multi-select) via Android's system document picker
- Play/pause and seek controls from Media3 PlayerView
- Next video button
- External subtitle picker; basic SRT, VTT, TTML, SSA/ASS MIME selection
- Embedded subtitle tracks supported when the underlying decoder/container supports them
- No INTERNET permission, ads, accounts, analytics, or network code

## Important compatibility note
This is not literally an “every format” decoder. Media3 uses Android/platform decoders and supports common formats; actual codec support varies by device. ASS/SSA rendering and less-common subtitle formats may need a dedicated libass/FFmpeg integration, which is not included in this starter. A true broad-format release requires adding and testing an FFmpeg-based decoder build and handling codec licensing/patents where applicable.

## Build the APK
The included GitHub Actions workflow builds a debug APK. Push this folder to a GitHub repository, then open **Actions → Build Android APK → Run workflow** (or push to `main`). Download the `UniversalPlayer-debug-apk` artifact and install `app-debug.apk` on your Android phone. You may need to allow installation from that source.

You can also open the folder in Android Studio and run **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
