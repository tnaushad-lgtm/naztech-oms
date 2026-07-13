@echo off
REM ============================================================
REM  QuestDB — the tick time-series (market_tick) behind candles,
REM  the trade tape and any historical market-data query.
REM
REM    :9000  web console + SQL over HTTP   (http://localhost:9000)
REM    :9009  ILP ingest (the OMS writes ticks here)
REM    :8812  Postgres wire (any SQL client)
REM
REM  It is a plain Java process from the Maven artifact — no install,
REM  no Docker. Data lives in questdb-data\ (gitignored).
REM
REM  Start this BEFORE the backend if you want tick history; without
REM  it the OMS still runs and candles fall back to the trade table.
REM ============================================================
cd /d "%~dp0"

set "QDB_VERSION=8.1.1"
set "QDB_JAR=%USERPROFILE%\.m2\repository\org\questdb\questdb\%QDB_VERSION%\questdb-%QDB_VERSION%.jar"

if not exist "%QDB_JAR%" (
  echo Fetching QuestDB %QDB_VERSION% from Maven Central...
  call mvn -q dependency:get -Dartifact=org.questdb:questdb:%QDB_VERSION%
)

echo Stopping anything on port 9000...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":9000" ^| findstr LISTENING') do taskkill /F /PID %%a >nul 2>&1

echo Starting QuestDB...
start "QuestDB :9000 [tick store]" cmd /k java -p "%QDB_JAR%" -m io.questdb/io.questdb.ServerMain -d "%~dp0questdb-data"

echo.
echo ============================================================
echo  QuestDB starting.
echo   - Web console:  http://localhost:9000
echo   - Ticks land in the  market_tick  table as the market trades.
echo   - Try:  SELECT * FROM market_tick ORDER BY ts DESC LIMIT 20;
echo ============================================================
timeout /t 5 /nobreak >nul
