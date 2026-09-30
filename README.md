# ⌚ Galaxy Watch to Mac Air Controller

[![Platform](https://img.shields.io/badge/Platform-Wear%20OS%20%7C%20macOS-000000.svg?style=flat&logo=apple)](https://github.com/Happy123455/GalaxyMacController)
[![Language](https://img.shields.io/badge/Language-Kotlin%20%7C%20Python-blue.svg?style=flat)](https://github.com/Happy123455/GalaxyMacController)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

<p align="center">
  <img src="assets/infographic.jpg" alt="Galaxy Watch 8 Classic to MacBook Air Infographic" width="100%">
</p>

Transform your **Samsung Galaxy Watch** (Wear OS 4 / 5 / Galaxy Watch 4, 5, 6, 7, 8 Ultra/Classic) into a high-precision, low-latency **Air Controller & Spatial Mouse** for your **Mac**.

Built with real-time IMU sensor fusion, DSP anti-tremor filtering, macOS Accessibility magnetic button snapping, physical bezel rotary wheel decoding, and wrist-twist 3D scrolling.

---

## ✨ Features & Gesture Map

| Gesture / Input | macOS Action | Mechanism |
| :--- | :--- | :--- |
| **Move Arm** | **Air Mouse Pointer** | 200 Hz gyroscope, bias tracking, speed-adaptive tremor filter, smooth pointer acceleration & gravity tilt compensation |
| **🧲 Button Snapping** *(opt-in: `--snap`)* | **Magnetic Cursor Lock** | macOS Accessibility API (`AXUIElement`) gently pulls a nearly-still cursor toward button centers |
| **⏱️ Dwell** *(opt-in: `--dwell`)* | **Auto Left Click** | Stationary hover for $\ge 1.0\text{s}$ executes left click without any hand movement |
| 🤏🤏 **Double Pinch** | **Left Click** | Two rapid thumb + index taps (cadence window filtered to avoid flesh recoil bounce) |
| 🤏🤏🤏 **Triple Pinch** | **Right Click** | Three rapid pinches opens context menus |
| 🤏 **Single Pinch** | *(Ignored)* | Single taps are filtered out to prevent accidental misclicks |
| ✊ **Violent Shake** | **Grab / Drag & Select Toggle** | High-acceleration burst ($\ge 17.5\text{ m/s}^2$) toggles left-click hold down for grabbing windows/files |
| 🌀 **Wrist Twist (Clockwise)** | **Scroll Down** | Twist around the forearm (not up/down aiming) for continuous downward scrolling |
| 🌀 **Wrist Twist (Anti-Clockwise)** | **Scroll Up** | Twist the other way to scroll up. Direction can be flipped in *Sensitivity & Speeds* |
| ⚙️ **Physical Rotating Bezel** | **Precision Stepped Scroll** | Wear OS rotary encoder event mapped to native macOS session scroll wheel |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────┐
│              Samsung Galaxy Watch (Wear OS)             │
│  - Jetpack Compose UI with Hardware Rotary Interceptor   │
│  - Gyroscope & Linear Acceleration at 60 Hz             │
│  - 3D Rotation Vector & Dynamic Neutral Baseline        │
│  - DSP Pulse Segmentation & Debounce Cadence Window     │
└────────────────────────────┬────────────────────────────┘
                             │  UDP over Wi-Fi  -or-  TCP over ADB tunnel
                             ▼
┌─────────────────────────────────────────────────────────┐
│                   macOS Host Companion                  │
│  - Background Daemon via LaunchAgent (auto-starts)     │
│  - Native CGEvent injection (moves, clicks, drag, scroll)│
│  - Accessibility API (AXUIElement) Sticky Buttons       │
│  - 1-Second Dwell Auto-Clicker                          │
│  - Local Neural Studio Dashboard (http://localhost:8766) │
└─────────────────────────────────────────────────────────┘
```

---

## 🚀 Getting Started

### 1. macOS Companion Setup

#### Prerequisites
- macOS 13+ (Ventura, Sonoma, Sequoia)
- Python 3.10+ (the controller needs only the standard library)
- Optional, for the ML dashboard: `pip install -r mac-server/requirements.txt`

#### Quick Start (Foreground)
```bash
cd mac-server
python3 mac_controller_server.py            # add --snap and/or --dwell to enable those assists
```
Other flags: `--scroll-scale 2.0`, `--dwell-time 1.2`, `--no-dashboard`, `-v` (packet-rate stats).

#### Install as Silent Background Service (Auto-starts on Mac login)
```bash
cd mac-server
./install_service.sh
```
> To uninstall later: `./uninstall_service.sh`

#### ⚠️ macOS Accessibility Permissions
1. Open **System Settings** > **Privacy & Security** > **Accessibility**.
2. Ensure **Python** (or **Terminal** if running directly) is toggled **ON** 🟢.
   *(macOS requires accessibility permission to dispatch native clicks and scroll events to other applications).*

---

### 2. Galaxy Watch App Setup

#### Prerequisites
- Android Studio Ladybug or newer / Android SDK command-line tools
- Galaxy Watch Wi-Fi turned **on** and joined to the same network as your Mac.
  It's fine for the watch to stay paired to your phone: the app asks Wear OS for the Wi-Fi
  network directly instead of going through the phone's Bluetooth connection.

#### Option A: Build & Deploy via ADB (Quickest)
1. Enable **Developer Options** and **Wireless Debugging** on your watch.
2. Pair & connect your Mac to the watch over ADB:
   ```bash
   adb connect <WATCH_IP>:<WATCH_PORT>
   ```
3. Build and install:
   ```bash
   cd wear-os-app
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

#### Option B: Android Studio
1. Open the `wear-os-app/` directory in Android Studio.
2. Select your Galaxy Watch target from the device dropdown.
3. Click **Run** ▶️.

The watch app automatically discovers your Mac over Wi-Fi (subnet broadcast + the last Mac it connected to).

> **Do I need Wireless Debugging?** Only to install the app, and for the optional ADB tunnel below.
> Normal day-to-day use over Wi-Fi does not need it.

#### Can't connect? Use the ADB tunnel
If the watch keeps showing *Searching Mac...* (guest Wi-Fi, router client isolation, etc.),
tunnel the connection through ADB instead:
```bash
adb connect <WATCH_IP>:<PORT>      # Wireless debugging must be on
./mac-server/adb_tunnel.sh
```
The watch switches to **ADB tunnel** automatically within ~2 seconds.

---

## 🧠 Neural Studio Web Dashboard

When the Mac server is running, open:
```text
http://localhost:8766
```
- Real-time gesture waveform oscilloscope
- PyTorch 1D-CNN tap classifier confidence
- Live sensor telemetry & click inspection

---

## 🛠️ Configuration & Customization

Inside the Watch App settings page or `AppSettings.kt`:
- **Pinch Sensitivity Threshold**: Calibrate trigger force ($2.0 - 6.0\text{ m/s}^2$).
- **Normal / Aiming Speed**: the pointer gain curve blends smoothly from *Aiming* speed (slow, precise hand movement) to *Normal* speed (fast sweeps).
- **Tilt Compensation**: keeps horizontal = horizontal even with the wrist turned. On by default.
- **Twist Scroll direction**: flip if wrist twist scrolls the wrong way.
- **ML Calibration**: learns your pinch force *and* your multi-pinch rhythm (the double/triple pinch window).
- **Dwell Timer** (Mac): `--dwell --dwell-time 1.0`.

### 🎯 Tips for precision
- Hold still for about half a second after launching the app, so the gyro drift estimate settles.
- If the cursor feels too fast when aiming, lower **Aiming Speed** (6 = most precise).
- Run the **ML Calibration** once. It tunes the pinch threshold and timing to your hand, which removes most missed or phantom clicks.
- If the Mac is also paired to the watch as a Bluetooth mouse, the app automatically sends Bluetooth reports only when the Wi-Fi/ADB link is down, so input is never doubled.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE) - see the LICENSE file for details.
