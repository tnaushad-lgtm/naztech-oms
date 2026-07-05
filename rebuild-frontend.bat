@echo off
REM ============================================================
REM  NAZTECH OMS - rebuild & restart ONLY the frontend (port 3060)
REM  Run this ONCE to apply the same-origin reverse-proxy change
REM  (next.config.js rewrites + lib/api.ts) so the whole app can be
REM  shared behind a single Cloudflare/ngrok link.
REM
REM  This does NOT touch the Java backend or your live FIX session.
REM ============================================================
cd /d "%~dp0"

echo Freeing port 3060 (stopping the old frontend if it is running)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :3060 ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1

echo Rebuilding the frontend (production build, ~1 min)...
start "OMS Frontend :3060" cmd /k "cd /d %~dp0frontend && npm run build && npm run start"

echo.
echo ============================================================
echo  Frontend rebuilding, then serving on:  http://localhost:3060
echo  Backend + FIX session were left running untouched.
echo  Once it says 'Ready', run  share-cloudflare.bat  to get a
echo  public link for your boss.
echo ============================================================
echo.
pause
