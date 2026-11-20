# AGENTS.md — Permanent Rules for CarCastMirror

## PERMANENT AUTOMATIC GITHUB RELEASE RULE

**This is a permanent instruction for the CarCastMirror project. Follow it for every future development task without requiring separate user confirmation.**

Whenever the user requests ANY change, bug fix, UI improvement, new feature, or performance optimization in CarCastMirror, automatically perform the complete release workflow after implementing it.

The user should NOT need to repeatedly say "Build APK", "Update GitHub", or "Publish Release".

---

### Required Workflow for Every Future Change

#### Step 1 — Implement
- Make the requested source-code changes.
- Preserve previously working functionality (AirPlay via UxPlay, Android Sender/Receiver, internal audio, HD/FHD video, reverse touch, payment QR, smart switching, updates).
- Do not modify unrelated components unnecessarily.
- Add or update relevant tests.

#### Step 2 — Validate
- Run Gradle unit tests (`.\gradlew.bat testDebugUnitTest --no-daemon`).
- Run the appropriate build checks.
- Verify affected components.
- Stop and report meaningful errors if validation fails.

#### Step 3 — Version Bump
- Fetch existing GitHub releases and tags (`git tag -l` and `gh release list`).
- Determine the next unused `versionName` and `versionCode`.
- Automatically increment `versionCode` for every published APK.
- Keep `versionName`, `versionCode`, APK metadata, and Git tag consistent.
- Never reuse an existing release tag.

#### Step 4 — Build Signed Release APK
- Build the Android release APK locally (`assembleRelease`).
- Verify the package name is `com.carcast.mirror`.
- Verify signing certificate compatibility with existing installed releases (`SHA-256: 82:D5:7A:48:63:A3:CF:34:71:A6:29:3F:BE:CA:C3:AB:8F:44:21:8D:62:EF:28:FD:D2:2F:2E:85:C0:D8:92:8E`).
- Generate the APK SHA-256 checksum.
- Never publish unsigned, incorrectly signed, or debug APKs as production updates.

#### Step 5 — Automatically Publish to GitHub
- Repository: `https://github.com/softwork-05/car-cast-`
- Commit the approved source-code changes.
- Push the source branch safely (`git push origin main`).
- Create the new release tag (`vX.Y.Z`).
- Use the existing `release.ps1` direct-publishing workflow with GitHub CLI (`gh`).
- Upload the verified signed APK and checksums (`CarCastMirror.apk`, `CarCastMirror-vX.Y.Z.apk`, checksums) to GitHub Releases.
- For Windows receiver releases, package and upload Windows assets where applicable.
- Never force-push or overwrite published tags.
- Never publish secrets, keystores, or credentials.

#### Step 6 — Verify Published Release
After publishing:
- Confirm the GitHub Release exists.
- Confirm the APK asset is downloadable.
- Verify its version and checksum.
- Confirm the in-app update endpoint reports the new release.
- Confirm the older installed version should detect UPDATE AVAILABLE.
- Confirm the newer installed version should display YOU'RE UP TO DATE.
- If physical-device verification is unavailable, clearly state that it still needs testing.

#### Step 7 — Final Report
After every development task, automatically provide:
- Features or fixes completed
- New release version
- GitHub release URL
- Direct APK download URL
- APK checksum
- Signing verification result
- Automated test results
- Any remaining physical-device tests

---

### Important Publishing Controls
- Do not publish if tests fail.
- Do not publish unfinished or unverified code.
- Do not publish if signing verification fails.
- Do not force-push or delete existing releases.
- Do not publish sensitive information.
- If a major release risk or destructive operation requires confirmation, ask before proceeding.
- If GitHub authentication or permissions are unavailable, report the blocker rather than claiming success.

---

### Core Features to Preserve Across Every Release
1. Working iPhone AirPlay mirroring via native UxPlay engine.
2. Android Sender and Receiver modes with WebSocket streaming.
3. Screen rotation and responsive UI across 16:9 car screens, ultra-widescreens, TVs, tablets, and phones.
4. HD (720p) and Full HD (1080p) video streaming with adaptive profiles.
5. Internal audio routing to Car LCD / TV speakers with phone speaker suppression.
6. Reverse Touch Control via Android Accessibility service.
7. Payment QR display (NayaPay & Easypaisa side panels).
8. Smart Sender/Receiver switching.
9. Automatic device discovery (NSD/mDNS across Car LCD, Android TV, and Windows PC).
10. In-app automatic update checking with versionCode validation.
