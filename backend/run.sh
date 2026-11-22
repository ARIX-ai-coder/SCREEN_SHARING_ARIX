#!/usr/bin/env bash
# macOS / Linux launcher
cd "$(dirname "$0")"
[ -d .venv ] || python3 -m venv .venv
. .venv/bin/activate
pip install -q -r requirements.txt
echo "ARIX server starting. On the phone use  http://THIS-COMPUTER-LAN-IP:8000"
exec uvicorn main:app --host 0.0.0.0 --port 8000
