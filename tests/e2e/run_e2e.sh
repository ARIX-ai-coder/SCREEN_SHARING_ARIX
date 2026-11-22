#!/usr/bin/env bash
# Starts the backend on port 8765, runs the Chromium e2e test, stops the backend.
set -u
cd "$(dirname "$0")/../../backend"
python3 -m uvicorn main:app --host 127.0.0.1 --port 8765 --log-level warning > /tmp/arix_e2e_server.log 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
UP=0
for i in $(seq 1 60); do
  if curl -s -f -o /dev/null --noproxy '*' http://127.0.0.1:8765/api/health; then UP=1; break; fi
  kill -0 $SERVER 2>/dev/null || break
  sleep 0.5
done
if [ $UP -ne 1 ]; then echo "backend did not start:"; cat /tmp/arix_e2e_server.log; exit 2; fi
cd ../tests/e2e && node e2e.js http://127.0.0.1:8765
RC=$?
echo "--- server log (warnings/errors only) ---"; cat /tmp/arix_e2e_server.log
exit $RC
