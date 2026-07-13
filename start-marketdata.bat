@echo off
REM ============================================================
REM  Start ONLY the OMS Market-Data + AI service (Python, :8091).
REM  Use this if the market-data window crashed but the backend
REM  (:8090) and frontend (:3060) are still running.
REM ============================================================
cd /d "%~dp0"

echo Stopping anything on port 8091...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8091" ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1

echo Starting OMS Market-Data + AI (Python, port 8091)...
start "OMS MarketData :8091" cmd /k "cd /d %~dp0marketdata && uvicorn app:app --host 0.0.0.0 --port 8091"

echo.
echo Market-data service starting in its own window. Give it ~10s.
timeout /t 3 /nobreak >nul
