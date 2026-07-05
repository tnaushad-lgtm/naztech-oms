@echo off
REM ============================================================
REM  NAZTECH OMS - share the running app over the internet via a
REM  Cloudflare Quick Tunnel (no account, no signup).
REM
REM  PRE-REQS (already true if the app is up):
REM    - Backend :8090 running and (for the FIX demo) connected.
REM    - Frontend :3060 running AFTER you ran rebuild-frontend.bat once.
REM
REM  This prints an https://XXXX.trycloudflare.com link.
REM  Copy that link and send it to your boss - he just opens it.
REM  KEEP THIS WINDOW OPEN: closing it ends the tunnel.
REM ============================================================
cd /d "%~dp0"

REM --- locate or fetch cloudflared ---
where cloudflared >nul 2>&1
if %ERRORLEVEL%==0 ( set "CF=cloudflared" & goto run )
if exist "%~dp0cloudflared.exe" ( set "CF=%~dp0cloudflared.exe" & goto run )

echo cloudflared was not found. Downloading the official Cloudflare binary...
echo Source: https://github.com/cloudflare/cloudflared/releases/latest
powershell -Command "Invoke-WebRequest -Uri 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile '%~dp0cloudflared.exe'"
if not exist "%~dp0cloudflared.exe" (
  echo.
  echo Download failed. Install it once with:   winget install Cloudflare.cloudflared
  echo then run this file again.
  pause
  exit /b 1
)
set "CF=%~dp0cloudflared.exe"

:run
echo.
echo ============================================================
echo  Opening a public tunnel to  http://localhost:3060 ...
echo  Look below for a line like:
echo       https://something-random.trycloudflare.com
echo  Copy THAT url and send it to your boss.
echo.
echo  Boss logs in with the app credentials, e.g.:
echo       dealer1 / demo123   (or an investor login)
echo  All trades route to FIXSIM (simulated) - no real money.
echo.
echo  Keep this window open for the whole test. Ctrl+C ends it.
echo ============================================================
echo.
"%CF%" tunnel --url http://localhost:3060
pause
