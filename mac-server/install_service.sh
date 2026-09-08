#!/bin/bash
# Install LaunchAgent service to run the server automatically on Mac login
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
PLIST="$HOME/Library/LaunchAgents/com.galaxymac.controller.plist"
PYTHON_BIN="$(which python3)"

if [ -z "$PYTHON_BIN" ]; then
    echo "❌ Error: python3 not found on PATH."
    exit 1
fi

mkdir -p "$HOME/Library/LaunchAgents"

cat <<EOF > "$PLIST"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.galaxymac.controller</string>
    <key>ProgramArguments</key>
    <array>
        <string>${PYTHON_BIN}</string>
        <string>-u</string>
        <string>${DIR}/mac_controller_server.py</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PYTHONUNBUFFERED</key>
        <string>1</string>
    </dict>
    <key>WorkingDirectory</key>
    <string>${DIR}</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${DIR}/server.log</string>
    <key>StandardErrorPath</key>
    <string>${DIR}/server.log</string>
</dict>
</plist>
EOF

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
echo "✅ Galaxy Mac Controller background service installed & running!"
echo "   Logs: tail -f ${DIR}/server.log"
