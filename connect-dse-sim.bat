@echo off
REM ============================================================
REM  Start the OMS backend in FULLY-CONNECTED (exchange) mode:
REM    - orders route to FIXSIM over FIX  (exchange.mode=dse-cert)
REM    - market data comes from the ITCH simulator (itch.enabled=true)
REM    - FIX session logs on to FIXSIM     (fix.enabled=true, from application.yml)
REM
REM  Settings are passed as ENV VARS (Spring Boot relaxed binding) to avoid
REM  Windows batch quoting problems.  For the normal polished demo use restart-backend.bat.
REM ============================================================
cd /d "%~dp0"

echo Stopping anything on port 8090...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8090" ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1

set "EXCHANGE_MODE=dse-cert"
set "ITCH_ENABLED=true"

echo Starting OMS Backend in DSE-CERT (FIX + ITCH) mode...
start "OMS Backend :8090 [FIX+ITCH]" cmd /k "cd /d %~dp0backend && mvn spring-boot:run"

echo.
echo ============================================================
echo  Backend starting in FIX+ITCH mode (~40s).
echo   - Wait for the FIX session to log on (FIXSIM portal dot = green)
echo     BEFORE placing orders, so they can be routed over FIX.
echo   - Orders then go to FIXSIM; watch backend\fixlog\ for the messages.
echo ============================================================
timeout /t 5 /nobreak >nul
