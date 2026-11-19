# CarCastMirror Release Notes & Verification History

## Version 1.0.9 (Build 9) — 19 November 2026
- **Payment QR Side Panels**: Intelligently displays NayaPay (Left) and Easypaisa (Right) QR codes in unused pillarbox margins when mirroring portrait phones on widescreen Car LCDs, maintaining exact aspect ratios, pixel-perfect reverse-touch mapping, and zero overlay interference. Features on-screen toggle in the floating control pill and in Settings (default OFF).
- **Direct Internal Audio Output to Car LCD**: Dedicated low-latency `AudioTrack` with automotive `AudioFocus` request, jitter-free buffer queue, and user-configurable **Audio Output** route (**Car LCD Only**, **Phone Only**, **Both Devices**). In "Car LCD Only" mode, phone media volume is suppressed while streaming and automatically restored upon disconnect.
- **Full HD Quality Streaming**: Redesigned dimension calculations to fit within 1920x1080 bounds preserving phone aspect ratio without exceeding hardware encoder Level 4.2 macroblock limits. Full HD streams at 8.5 Mbps / 30 FPS, HD at 4.5 Mbps / 30 FPS, and Smooth at 6.0 Mbps / 60 FPS.
- **Fully Responsive UI**: Jetpack Compose layouts dynamically adapt across small 16:9 car screens (800x480, 1024x600), ultra-widescreens (21:9, 24:9), tablets, and phones with scroll management and zero clipping.
- **Live Diagnostics**: Added real-time tracking of audio packets sent/received, bytes transferred, output route status, encoder resolution, frame rates, and dropped frames.
- **Security & Update Compatibility**: Signed with official key matching SHA-256 certificate `82:D5:7A:48:63:A3:CF:34:71:A6:29:3F:BE:CA:C3:AB:8F:44:21:8D:62:EF:28:FD:D2:2F:2E:85:C0:D8:92:8E`.

---

## Version 1.0.8 (Build 8) — 18 November 2026
- **Scan to Pay**: Integrated full-screen payment modal on Receiver screen with crisp, responsive NayaPay (Orange) and Easypaisa (Green) QR codes for car displays and phones.
- **Embedded Permanent QR Assets**: Included lossless, pixel-perfect, local PNG QR codes for Muhammad Mustajab Haider (`mustajab.512@nayapay` / `PK18NAYA1234503349299961` and Easypaisa / `PK22TMFB0000000052413891`). Both QR codes decode with 100% accuracy using ZXing standard.
- **In-App Auto-Update Verification**: `versionCode` bumped to 8 (`versionName = "1.0.8"`). Release published to GitHub Releases API with automated numeric version comparison (`releaseCode > installedCode`).
- **Signature Security Verified**: APK signed with official key matching certificate SHA-256 `82:D5:7A:48:63:A3:CF:34:71:A6:29:3F:BE:CA:C3:AB:8F:44:21:8D:62:EF:28:FD:D2:2F:2E:85:C0:D8:92:8E`.
- **DirectPublish Automation**: Added fast local release build and direct GitHub CLI upload to `release.ps1` to bypass CI queue bottlenecks while maintaining strict certificate checks.

---

## Older Diagnostics & Source Fixes — 17 November 2026

- AirPlayCrypto: Ed25519 receiver identity now persists across app restarts using app-private SharedPreferences; initialization occurs in CarCastApplication. It is not exposed in logs.
- AirPlayCrypto: stage 1/2 pair-verify state is now **per TCP connection**, rather than shared by all clients. Real Ed25519 signature verification and negative/replay rejection retained.
- AirPlayRtspServer: transient `/pair-setup` with a 32-byte client key now returns a 32-byte server Ed25519 public key (previous response was empty).
- RTSP listener: routes ports 5000 and 7000, validates payload lengths, detects truncated payloads and headers, limits RTSP line size and reports real per-port listener state.
- Removed fabricated `fp-setup` 142/32-byte packets and fake `RECORD -> mirroring started` success. Unsupported FairPlay/media setup now returns meaningful RTSP errors instead of falsely declaring success.
- Logger: avoids sharing a non-thread-safe SimpleDateFormat instance across socket threads, and retains 200 log lines.
- Added a full cryptographic pair-verify round-trip JUnit test.

## Tests actually performed in this environment

The edited Kotlin crypto + RTSP server code was compiled against BouncyCastle with lightweight Android/plist test stubs (not a full Android build).

**Crypto tests:** deterministic identity, malformed first-stage rejection, 96-byte server reply, Ed25519 signature validation, valid second-stage verification, replay rejection, cross-session isolation and cross-session invalid signature: all passed.

**Local socket tests:** port 7000 OPTIONS, port 5000 transient pair-setup, port 5000 explicit unsupported FairPlay response and error logging: all passed.

**Not tested:** Android Gradle APK build, physical iPhone connection, real Apple FairPlay handshake, audio/video rendering, compatibility with iOS 27.

## Critical missing functionality

The original source did not include a real FairPlay handshake implementation or the real AirPlay video control + key negotiation + stream framing/decryption pipeline. The UDP RTP receiver simply stripped 12 bytes and forwarded the remainder to H.264 MediaCodec: this is NOT an AirPlay mirroring decoder. Fixing a few Kotlin methods alone cannot make that receiver interoperate with an iPhone.

**For actual iPhone mirroring, integrate a proven AirPlay backend rather than fabricate more protocol replies.** See AIRPLAY_ENGINE_MIGRATION.md.

## Use in Antigravity

1. Open the source folder (this zip is source-only; excludes build caches, signing secrets and APK outputs).
2. Open `AIRPLAY_ENGINE_MIGRATION.md`.
3. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` on your machine with Android SDK and Gradle internet/dependencies installed.
4. Do not label the output a working receiver until a native protocol engine is integrated and tested on a physical iPhone.

## Privacy/security

Do not paste private Ed25519 seeds, plaintext session keys, or protected media into debug logs. Review at-rest key storage/backup policy before production distribution.
