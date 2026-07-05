@echo off
REM ============================================================
REM  NAZTECH OMS - start all 3 services in separate windows
REM  Frontend runs in PRODUCTION mode (build + start).
REM  Double-click this file, OR run it from a terminal.
REM  (To run the frontend in DEV mode instead, see the note below.)
REM ============================================================
cd /d "%~dp0"

echo Starting OMS Backend (Java, port 8090)...
start "OMS Backend :8090" cmd /k "cd /d %~dp0backend && mvn spring-boot:run"

echo Starting OMS Market-Data + AI (Python, port 8091)...
start "OMS MarketData :8091" cmd /k "cd /d %~dp0marketdata && uvicorn app:app --host 0.0.0.0 --port 8091"

echo Starting OMS Frontend (Next.js PRODUCTION, port 3060)...
REM  Builds first, then serves the optimised production build (binds all interfaces for LAN access).
start "OMS Frontend :3060" cmd /k "cd /d %~dp0frontend && npm run build && npm run start"
REM  --- DEV MODE alternative (hot-reload, slower): comment out the line above and use:
REM  start "OMS Frontend :3060" cmd /k "cd /d %~dp0frontend && npm run dev -- -H 0.0.0.0"

echo.
echo ============================================================
echo  All three launched in separate windows.
echo  The frontend builds first (~1 min) then starts, so allow a
echo  little longer on the first launch. Then open:
echo.
echo       http://localhost:3060
echo    (LAN users: http://YOUR-PC-IP:3060)
echo.
echo  Login: dealer1 / demo123   (or use the quick-login buttons)
echo ============================================================
echo.
timeout /t 90 /nobreak >nul
start "" "http://localhost:3060"
