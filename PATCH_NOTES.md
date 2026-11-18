# CarCastMirror: verified source fixes — 17 November 2026

**Status: development/diagnostic source patch. NOT a working AirPlay mirroring APK.**

## Fixes performed

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
