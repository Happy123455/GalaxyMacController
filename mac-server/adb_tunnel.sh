#!/bin/bash
# Route the watch's connection to this Mac through ADB instead of Wi-Fi discovery.
# Use this when the watch can't find the Mac over Wi-Fi (guest networks, client isolation,
# watch stuck on the phone's Bluetooth connection, etc.).
#
# 1. On the watch: Settings > Developer options > Wireless debugging > ON, pair once.
# 2. On the Mac:   adb pair <WATCH_IP>:<PAIR_PORT>   then   adb connect <WATCH_IP>:<PORT>
# 3. Run this script. The watch app switches to "ADB tunnel" within ~2 seconds.
PORT="${1:-8765}"

if ! command -v adb >/dev/null 2>&1; then
    echo "❌ adb not found. Install it with: brew install --cask android-platform-tools"
    exit 1
fi

DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
    echo "❌ No ADB device connected. Connect the watch first: adb connect <WATCH_IP>:<PORT>"
    exit 1
fi

for d in $DEVICES; do
    adb -s "$d" reverse "tcp:${PORT}" "tcp:${PORT}" && echo "✅ Tunnel active on $d (watch 127.0.0.1:${PORT} -> Mac :${PORT})"
done
echo "   Keep mac_controller_server.py running. Re-run this after the watch reconnects."
