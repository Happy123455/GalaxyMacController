#!/bin/bash
# Start script for Galaxy Watch to Mac Air Controller
cd "$(dirname "$0")"
echo "Starting Galaxy Watch Mac Controller Server..."
python3 mac_controller_server.py
