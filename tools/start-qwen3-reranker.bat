@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo   Qwen3-Reranker Service Starter
echo ============================================================
echo.

set "MODEL_NAME=B-A-M-N/Qwen3-Reranker-0.6B-fp16:latest"
set "PORT=11435"
set "HOST=127.0.0.1"

REM [1/5] Find Ollama installation directory
echo [1/5] Locating Ollama installation...

where ollama >nul 2>&1
if %errorlevel% equ 0 (
    for /f "delims=" %%i in ('where ollama') do set "OLLAMA_DIR=%%~dpi"
)

if not defined OLLAMA_DIR (
    if exist "C:\Program Files\Ollama\ollama.exe" set "OLLAMA_DIR=C:\Program Files\Ollama\"
    if exist "E:\ollama\ollama.exe" set "OLLAMA_DIR=E:\ollama\"
    if exist "%LOCALAPPDATA%\Ollama\ollama.exe" set "OLLAMA_DIR=%LOCALAPPDATA%\Ollama\"
)

if not exist "!OLLAMA_DIR!ollama.exe" (
    echo [ERROR] Ollama installation not found
    echo   Ensure Ollama is installed and in PATH
    goto :error
)
echo [INFO] Ollama directory: !OLLAMA_DIR!

REM [2/5] Locate llama-server.exe
echo [2/5] Locating llama-server.exe...

set "LLAMA_SERVER="
if exist "!OLLAMA_DIR!lib\ollama\llama-server.exe" (
    set "LLAMA_SERVER=!OLLAMA_DIR!lib\ollama\llama-server.exe"
) else if exist "!OLLAMA_DIR!llama-server.exe" (
    set "LLAMA_SERVER=!OLLAMA_DIR!llama-server.exe"
)

if not exist "!LLAMA_SERVER!" (
    echo [ERROR] llama-server.exe not found
    echo   Searched: !OLLAMA_DIR!lib\ollama\llama-server.exe
    goto :error
)
echo [INFO] llama-server: !LLAMA_SERVER!

REM [3/5] Verify Ollama service is running
echo [3/5] Checking Ollama service...

powershell -NoProfile -Command "try { $r = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -Method Get -TimeoutSec 3 -ErrorAction Stop; Write-Host 'ok' } catch { Write-Host 'fail' }" > "%TEMP%\ollama_status.txt" 2>&1
set /p OLLAMA_STATUS=<"%TEMP%\ollama_status.txt"
del "%TEMP%\ollama_status.txt" 2>nul

if "!OLLAMA_STATUS!"=="fail" (
    echo [WARN] Ollama service not responding, starting...
    start "" "!OLLAMA_DIR!ollama.exe"
    echo [INFO] Waiting 5 seconds for Ollama to start...
    timeout /t 5 /nobreak >nul
)

REM [4/5] Resolve model GGUF path via PowerShell
echo [4/5] Resolving model GGUF path...
echo   Model: %MODEL_NAME%

powershell -NoProfile -Command ^
    "$name = '%MODEL_NAME%'; $info = ollama show $name --modelfile 2>&1; ^
    $line = $info | Select-String '^FROM\s+(.+)'; ^
    if ($line) { ^
        $path = $line.Matches.Groups[1].Value.Trim(); ^
        if ($path -match '^sha256-') { ^
            $home = $env:OLLAMA_MODELS; ^
            if (-not $home) { $home = Join-Path $env:USERPROFILE '.ollama\models' }; ^
            $path = Join-Path $home 'blobs' $path; ^
        }; ^
        Write-Output $path; ^
    } else { ^
        Write-Output 'ERROR'; ^
    }" > "%TEMP%\model_path.txt" 2>&1

set /p MODEL_PATH=<"%TEMP%\model_path.txt"
del "%TEMP%\model_path.txt" 2>nul

if "!MODEL_PATH!"=="ERROR" (
    echo [ERROR] Cannot resolve model path, model may not be pulled
    echo   Run: ollama pull %MODEL_NAME%
    goto :error
)
if not exist "!MODEL_PATH!" (
    echo [ERROR] Model file not found: !MODEL_PATH!
    goto :error
)
echo [INFO] GGUF path: !MODEL_PATH!

REM [5/5] Check port and start service
echo [5/5] Checking port %PORT%...

netstat -ano | findstr ":11435.*LISTENING" >nul 2>&1
if %errorlevel% equ 0 (
    echo [WARN] Port %PORT% is already in use
    echo   Stopping occupying process...
    for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":11435.*LISTENING"') do (
        powershell -NoProfile -Command "Stop-Process -Id %%p -Force -ErrorAction SilentlyContinue"
    )
    timeout /t 2 /nobreak >nul
)

echo.
echo ============================================================
echo   Starting Rerank Service...
echo ============================================================
echo   Model: %MODEL_NAME%
echo   Port: %PORT%
echo   URL: http://%HOST%:%PORT%/v1/rerank
echo ============================================================
echo.

REM Build the full command line for debugging
echo [DEBUG] Executable: "!LLAMA_SERVER!"
echo [DEBUG] Arguments: --rerank --model "!MODEL_PATH!" --port %PORT% --host %HOST% --ctx-size 8192 --no-webui
echo.

REM Start llama-server using PowerShell for reliable process launch
powershell -NoProfile -Command ^
    "Start-Process -FilePath '!LLAMA_SERVER!' ^
     -ArgumentList '--rerank','--model','!MODEL_PATH!','--port','%PORT%','--host','%HOST%','--ctx-size','8192','--no-webui' ^
     -WindowStyle Normal" 2>nul

echo [INFO] Process launched, waiting for service to become ready...
echo   Model loading may take 30-60 seconds on first run...
echo.

REM Wait for service to become ready (max 120 seconds)
set /a WAIT_COUNT=0
:wait_loop
timeout /t 2 /nobreak >nul
set /a WAIT_COUNT+=1

REM Use PowerShell for reliable HTTP check
powershell -NoProfile -Command ^
    "try { ^
        $r = Invoke-WebRequest -Uri 'http://127.0.0.1:%PORT%/v1/rerank' -Method Post -Body '{\"query\":\"ping\",\"documents\":[\"ping\"],\"top_n\":1}' -ContentType 'application/json' -TimeoutSec 3 -ErrorAction Stop; ^
        Write-Host 'ready'; ^
    } catch { ^
        if ($_.Exception.Response.StatusCode.value__ -eq 404) { ^
            Write-Host 'ready'; ^
        } else { ^
            Write-Host 'not_ready'; ^
        } ^
    }" > "%TEMP%\rerank_status.txt" 2>&1

set /p STATUS=<"%TEMP%\rerank_status.txt"
del "%TEMP%\rerank_status.txt" 2>nul

echo [DEBUG] Check !WAIT_COUNT!/60: status=!STATUS!

if "!STATUS!"=="ready" goto :ready
if !WAIT_COUNT! geq 60 goto :timeout
goto :wait_loop

:ready
echo.
echo [SUCCESS] Service is ready!
echo.
echo   Test command:
echo   curl -X POST http://%HOST%:%PORT%/v1/rerank ^
echo     -H "Content-Type: application/json" ^
echo     -d "{\"query\":\"test\",\"documents\":[\"doc1\",\"doc2\"]}"
echo.
echo   Stop command: stop-qwen3-reranker.bat
echo.
echo ============================================================
echo   Closing this window does NOT stop the service
echo ============================================================
pause
exit /b 0

:timeout
echo.
echo [WARN] Service startup timeout (120s)
echo.
echo   Troubleshooting:
echo   1. Check if llama-server.exe is running: tasklist /fi "IMAGENAME eq llama-server.exe"
echo   2. Check if port %PORT% is listening: netstat -ano ^| findstr ":11435.*LISTENING"
echo   3. Try starting manually with the DEBUG info above
echo   4. Verify model is pulled: ollama list
pause
exit /b 1

:error
echo.
echo [ERROR] Startup failed, please check the output above
pause
exit /b 1
