# CarCast Mirror 🚗📱

**CarCast Mirror** is a production-ready Android application engineered specifically for **Android-based Car Head Units** and automotive LCD displays (landscape touchscreens 1024x600, 1280x720, 1920x720).

It transforms any Android car stereo or head unit into a **Dual-Platform Wireless Screen Mirroring Receiver** supporting both **Apple iOS (AirPlay)** and **Android (Hotspot-Ready Fast-Pair & Smart View)** with **Reverse Touch Control** and **In-App GitHub Auto-Updates**.

---

## 🌟 New & Core Features

### 1. Reverse Touch Control (Car LCD ➔ Phone)
* **Touch Phone from Car Screen**: When an Android device mirrors to the car, touches on the car LCD (`DOWN`, `MOVE`, `UP` gestures) are captured and dispatched back to the phone in real time over low-latency WebSocket.
* **Touch Toggle**: Auto-hiding floating pill includes a dedicated **Touch Control (ON/OFF)** button.

### 2. Android Hotspot-Ready Fast-Pair (No Smart View Limitations)
* **The Problem with Smart View**: Android phones (especially Samsung) block "Smart View" whenever Mobile Hotspot is turned ON due to Wi-Fi Direct radio conflicts.
* **The Solution**: CarCast Mirror includes an embedded Web Fast-Pair portal (`http://<car_ip>:8088`). Android users can scan the on-screen QR code or open Chrome to mirror their screen at 60 FPS **even with Mobile Hotspot active!**

### 3. Apple iOS Native AirPlay Receiver (UxPlay C/JNI Backend)
* **Zero Third-Party Apps Required on iPhone/iPad**: Connects directly via Control Center "Screen Mirroring".
* **Real FairPlay & Mirroring Protocol**: Uses native UxPlay C core with PlayFair FairPlay authentication and AES RTP stream decryption (no mock handshakes).
* **Dual-Codec Hardware Acceleration**: Supports both H.264 (AVC) and H.265 (HEVC) MediaCodec decoding with adaptive low-latency fallback.
* **OpenGL EGL Surface Blitter**: Eliminates screen blackouts when rotating displays or toggling full screen.
* **Native mDNS (DNS-SD)**: Uses Android OS `NsdManager` with native TXT records for reliable zero-configuration discovery on port 7000.
* **Car Speaker Audio**: Low-latency audio decoding with Oboe playback engine.
* **Open Source & GPL-3.0 Compliant**: Built with UxPlay, libplist, OpenSSL, FFmpeg, and Oboe.

---

## 📦 APK Locations

* 📁 **[CarCastMirror-native-debug.apk](file:///c:/Users/Mustajab/Downloads/CarCastMirror_source_patch/CarCastMirror-native-debug.apk)** (~43.6 MB): Fully integrated CarCastMirror app with real native AirPlay engine, Android casting, and automotive UI.
* 📁 **[upstream-airplay-receiver.apk](file:///c:/Users/Mustajab/Downloads/CarCastMirror_source_patch/upstream-airplay-receiver.apk)** (~27.3 MB): Standalone upstream AirPlay receiver (v0.0.36) for independent baseline testing.

---

## 🚀 How to Install on Car Head Unit

1. Copy `CarCastMirror.apk` to a USB flash drive.
2. Plug the USB into the Car Head Unit and open the car's **File Manager**.
3. Tap `CarCastMirror-native-debug.apk` and hit **Install**.
4. *(Tip: Make sure to uninstall or force-stop any old mirroring apps on the car LCD so they don't fight for AirPlay port 7000).*

---

## 📜 License & Credits

CarCast Mirror is licensed under the [GNU General Public License v3.0](file:///c:/Users/Mustajab/Downloads/CarCastMirror_source_patch/CarCastMirror/LICENSE).

Credits and Upstream Projects:
- [android-airplay-server](https://github.com/jqssun/android-airplay-server) by jqssun for the native JNI AirPlay bridge, decoder selector, and video renderer pipeline.
- [UxPlay](https://github.com/FDH2/UxPlay) for the core AirPlay/RAOP server and PlayFair FairPlay implementation.
- [FFmpeg](https://ffmpeg.org) for lossless audio decoding.
- [Oboe](https://github.com/google/oboe) by Google for low-latency audio output.
- [libplist](https://github.com/libimobiledevice/libplist) by libimobiledevice for Apple property list parsing.
