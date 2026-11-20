# CarCast Mirror — Windows PC Receiver

Native high-performance desktop screen-mirroring receiver for **Windows 10/11**.

---

## 🚀 Features

- **Android Sender → Windows PC**: Stream Android screens at razor-sharp 1080p Full HD at up to 60 FPS with ultra-low latency.
- **Hardware-Accelerated Decoding**: Leverages W3C WebCodecs GPU hardware acceleration in modern Edge/Chrome/Firefox browsers for stutter-free, zero-lag playback.
- **Internal Audio Playback**: Direct low-latency stereo PCM audio streaming to Windows PC speakers.
- **Bidirectional Reverse Control**: Clicking and dragging on the Windows PC display window directly forwards multi-touch gestures (tap, swipe, scroll, drag) back to your Android phone via Android Accessibility Services.
- **mDNS Zero-Config Discovery**: Broadcasts `_carcast._tcp` DNS-SD service across local Wi-Fi / Hotspot. The CarCast Android app automatically discovers your PC and shows a "WINDOWS PC" badge with a 1-tap **Connect** button!
- **Display Flexibility**: Windowed mode, full-screen toggle (F11), and dynamic Aspect Ratio preservation (Fit / Fill).

---

## 🛠️ Quick Start

### 1-Click Launch (Recommended)

Simply double-click:
```cmd
run-windows-receiver.bat
```
This starts the local streaming backend and automatically opens a dedicated, frameless receiver window.

### Manual Command Line Launch
```bash
cd windows-receiver
npm install
node server.js
```
Then navigate to `http://localhost:8088` in your browser.

---

## 📱 How to Connect from Android Phone

1. Ensure your PC and phone are connected to the same Wi-Fi network (or connect your PC to your phone's **Mobile Hotspot**).
2. Open **CarCast Mirror** on your Android phone and select **Sender Mode**.
3. Under **Nearby CarCast Receivers**, your PC will appear automatically with a **WINDOWS PC** badge:
   ```text
   CarCast-PC (HOSTNAME)
   192.168.x.x:8080 • Windows PC   [Connect]
   ```
4. Tap **Connect**! Mirroring begins immediately.

---

## 🍏 iPhone AirPlay on Windows Investigation

### Architectural Breakdown
- **UxPlay Native Backend**: The core UxPlay AirPlay engine used in CarCastMirror on Android is written in C and depends on POSIX sockets, OpenSSL/BouncyCastle crypto, and GStreamer for video/audio pipeline decoding.
- **Running on Windows**:
  - Native Windows execution of UxPlay is possible via MSYS2 / MinGW-w64 with GStreamer Windows binaries (`gstreamer-1.0-msvc-x86_64`) and Apple Bonjour service for Windows (`dnssd.dll`).
  - Standalone Android `.so` libraries cannot be executed directly on Windows as they target the Android Bionic C library and Linux kernel (`arm64-v8a` / `armeabi-v7a` / Android `x86_64`).
  - For Windows users requiring iPhone AirPlay, the recommended setup is running the Windows receiver alongside Apple Bonjour or installing the open-source UxPlay Windows build. For the CarCast streaming protocol, Android Sender connects natively to `ws://<PC_IP>:8080` without requiring external runtimes.
