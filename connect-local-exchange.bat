@echo off
REM ============================================================
REM  Start the OMS backend routing orders to the LOCAL EXCHANGE:
REM    - orders go out over real FIX      (exchange.mode=dse-cert)
REM    - to the acceptor on this machine  (fix.host=127.0.0.1:15000)
REM    - market depth comes from ITCH     (itch.enabled=true)
REM
REM  RUN  start-local-exchange.bat  FIRST - the exchange must be
REM  listening before the OMS dials it.
REM
REM  Settings are passed as ENV VARS (Spring Boot relaxed binding).
REM  To go back to a real venue, override FIX_HOST / FIX_PORT /
REM  FIX_SENDER_COMP_ID / FIX_TARGET_COMP_ID - no code changes.
REM ============================================================
cd /d "%~dp0"

echo Stopping anything on port 8090...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8090" ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1

set "EXCHANGE_MODE=dse-cert"
set "ITCH_ENABLED=true"
set "FIX_ENABLED=true"
set "FIX_HOST=127.0.0.1"
set "FIX_PORT=15000"
set "FIX_SENDER_COMP_ID=TareqN"
set "FIX_TARGET_COMP_ID=FIXSIMDEMO"

echo Starting OMS Backend against the LOCAL EXCHANGE (FIX + ITCH)...
start "OMS Backend :8090 [FIX->local exchange]" cmd /k "cd /d %~dp0backend && mvn spring-boot:run"

echo.
echo ============================================================
echo  Backend starting (~40s), then it logs on to the local exchange.
echo   - Exchange Link page should turn GREEN (FIX Session Connected).
echo   - Place an order in the Trader Terminal: it goes out over FIX,
echo     comes back OPEN, then PARTIAL, then FILLED.
echo   - Watch the exchange window for the matching NEW / FILL lines.
echo ============================================================
timeout /t 5 /nobreak >nul
