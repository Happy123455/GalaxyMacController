#!/bin/bash
PLIST="$HOME/Library/LaunchAgents/com.galaxymac.controller.plist"

launchctl unload "$PLIST" 2>/dev/null || true
rm -f "$PLIST"
kill -9 $(pgrep -f "mac_controller_server.py") 2>/dev/null || true

echo "🛑 Galaxy Mac Controller service uninstalled and stopped."
