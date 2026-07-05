@echo off
REM ============================================================
REM  Restart ONLY the OMS Backend (Java, port 8090).
REM  Use this after changing backend code (it recompiles via
REM  mvn spring-boot:run). Frontend & market-data keep running.
REM ============================================================
cd /d "%~dp0"

echo Stopping anything on port 8090...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8090" ^| findstr LISTENING') do (
    echo   killing PID %%a
    taskkill /F /PID %%a >nul 2>&1
)

echo Starting OMS Backend (Java, port 8090)...
start "OMS Backend :8090" cmd /k "cd /d %~dp0backend && mvn spring-boot:run"

echo.
echo Backend restarting in its own window. Give it ~30s to boot,
echo then retry the AI Advisor / DSE Status.
echo.
timeout /t 4 /nobreak >nul
