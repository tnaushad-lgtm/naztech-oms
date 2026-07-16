@echo off
REM ============================================================
REM  NAZTECH BROKERAGE OMS  ->  nFIX (the DSE simulator, Jewel's app)
REM
REM  This is the real thing: our OMS acts as the broker (Naztech
REM  Brokerage Ltd.) and nFIX plays DSE. Orders go out over FIX to
REM  nFIX and come back as execution reports; market data streams
REM  in over ITCH/SoupBinTCP. Our security master is built from
REM  nFIX, so security.id == the DSE order-book id (1001-1300).
REM
REM    - FIX  order entry : 10.33.1.23:9014  (OMS -> DSE)
REM    - ITCH market data : 10.33.1.23:9012  (SoupBinTCP)
REM
REM  To point at REAL DSE later, change only the host/ports and the
REM  CompIDs/credentials below - no code changes.
REM ============================================================
cd /d "%~dp0"

echo Stopping anything on port 8090...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8090" ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1

REM ---- route orders over real FIX to nFIX ----
set "EXCHANGE_MODE=dse-cert"
set "FIX_ENABLED=true"
set "FIX_HOST=10.33.1.23"
set "FIX_PORT=9014"
set "FIX_SENDER_COMP_ID=OMS"
set "FIX_TARGET_COMP_ID=DSE"
set "FIX_SYMBOL=orderbookid"

REM ---- consume nFIX market data over ITCH / SoupBinTCP ----
set "ITCH_ENABLED=true"
set "ITCH_TRANSPORT=soupbintcp"
set "ITCH_HOST=10.33.1.23"
set "ITCH_PORT=9012"
set "ITCH_USER=OMS"
set "ITCH_SESSION="

echo Starting Naztech Brokerage OMS against nFIX (FIX + ITCH)...
start "OMS Backend :8090 [-> nFIX / DSE]" cmd /k "cd /d %~dp0backend && mvn spring-boot:run"

echo.
echo ============================================================
echo  Backend starting (~40s), then:
echo    - Exchange Link should show FIX logged on to DSE.
echo    - Market Watch fills as the ITCH feed streams in.
echo    - Place an order: it routes over FIX to nFIX and the
echo      blotter shows New -> Filled from nFIX's matching engine.
echo    NOTE: fills need nFIX's market to be OPEN (ask Jewel / run
echo    its demo day) - resting orders won't cross a closed book.
echo ============================================================
timeout /t 5 /nobreak >nul
