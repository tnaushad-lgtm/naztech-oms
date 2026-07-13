@echo off
REM ============================================================
REM  NAZTECH OMS - LOCAL EXCHANGE (FIX acceptor, port 15000)
REM
REM  Plays the exchange on THIS machine, so the OMS can route real
REM  orders over a real FIX session with no external dependency:
REM    - accepts the OMS logon        (FIXT.1.1 / FIX.5.0SP1)
REM    - acks each order, part-fills it, then completes it
REM    - handles cancels + cancel-rejects
REM
REM  This replaces the hosted FIXSIM trial, which refuses logons
REM  unless the account carries a paid subscription.
REM
REM  START THIS FIRST, then run connect-local-exchange.bat.
REM  Leave this window open - it IS the exchange.
REM ============================================================
cd /d "%~dp0"

echo Stopping anything on port 15000...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":15000" ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1

echo Starting the LOCAL EXCHANGE on port 15000...
start "LOCAL EXCHANGE :15000 [FIX acceptor]" cmd /k "cd /d %~dp0backend && mvn exec:java"

echo.
echo ============================================================
echo  Local exchange starting (~20s).
echo   - Wait for "LOCAL EXCHANGE is listening on port 15000".
echo   - Then run  connect-local-exchange.bat  to start the OMS.
echo   - Order/fill activity is printed in that window, and the
echo     raw FIX messages land in  backend\exchangelog\ .
echo ============================================================
timeout /t 5 /nobreak >nul
