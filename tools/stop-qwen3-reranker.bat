@echo off
setlocal

echo ============================================================
echo   Stop Qwen3-Reranker Service
echo ============================================================
echo.

REM Step 1: Stop process by port
echo [Step 1] Finding process on port 11435...

for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":11435.*LISTENING"') do (
    echo [INFO] Found process PID: %%p
    powershell -NoProfile -Command "Stop-Process -Id %%p -Force -ErrorAction SilentlyContinue"
    echo [OK] Stopped PID %%p
)

REM Step 2: Stop by process name (fallback)
echo.
echo [Step 2] Checking llama-server processes...

tasklist /fi "IMAGENAME eq llama-server.exe" 2>nul | find /i "llama-server.exe" >nul
if not errorlevel 1 (
    echo [INFO] Found llama-server process, stopping...
    taskkill /f /im "llama-server.exe" >nul 2>&1
    echo [OK] All llama-server processes stopped
) else (
    echo [INFO] No running llama-server process found
)

REM Step 3: Verify port is released
echo.
echo [Step 3] Verifying port 11435 is released...
timeout /t 2 /nobreak >nul

netstat -ano | findstr ":11435.*LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo [WARN] Port 11435 still in use, forcing release...
    for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":11435.*LISTENING"') do (
        powershell -NoProfile -Command "Stop-Process -Id %%p -Force -ErrorAction SilentlyContinue"
    )
    timeout /t 1 /nobreak >nul

    netstat -ano | findstr ":11435.*LISTENING" >nul 2>&1
    if not errorlevel 1 (
        echo [ERROR] Cannot release port 11435, please handle manually
        pause
        exit /b 1
    )
)

echo.
echo [SUCCESS] Qwen3-Reranker service has been stopped
echo [INFO] Port 11435 is now free
echo.
echo   To restart, run: start-qwen3-reranker.bat
echo ============================================================
pause
