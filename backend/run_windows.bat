@echo off
cd /d "%~dp0"
if not exist .venv python -m venv .venv
call .venv\Scripts\activate
pip install -q -r requirements.txt
echo.
echo ARIX server starting. On the phone use  http://THIS-PC-LAN-IP:8000   (run: ipconfig)
echo If Windows Firewall asks, allow access on PRIVATE networks.
echo.
uvicorn main:app --host 0.0.0.0 --port 8000
pause
