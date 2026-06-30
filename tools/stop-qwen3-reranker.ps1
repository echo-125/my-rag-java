$ErrorActionPreference = 'SilentlyContinue'
$PID_FILE = Join-Path $env:TEMP 'llama-rerank.pid'

function Write-Info($msg) { Write-Host "  $msg" }
function Write-Ok($msg) { Write-Host "  $msg" -ForegroundColor Green }
function Write-Err($msg) { Write-Host "  [ERROR] $msg" -ForegroundColor Red }

Write-Host '==========================================================='
Write-Host '  Stop Qwen3-Reranker Service'
Write-Host '==========================================================='
Write-Host ''

# ---- Step 1: Stop by PID file ----
Write-Host '[Step 1] Checking PID file...'

if (Test-Path $PID_FILE) {
    $servicePid = Get-Content $PID_FILE -ErrorAction SilentlyContinue
    Write-Info "Found PID file: PID=$servicePid"

    $proc = Get-Process -Id ([int]$servicePid) -ErrorAction SilentlyContinue
    if ($proc -and $proc.ProcessName -like '*llama*') {
        Write-Info "Stopping llama-server (PID: $servicePid)..."
        Stop-Process -Id ([int]$servicePid) -Force
        Write-Ok "Process stopped"
    } else {
        Write-Info "Process $servicePid is not running (stale PID file)"
    }

    Remove-Item $PID_FILE -Force -ErrorAction SilentlyContinue
} else {
    Write-Info 'No PID file found, falling back to port detection'
}

# ---- Step 2: Fallback - stop by port ----
Write-Host ''
Write-Host '[Step 2] Checking port 11435...'

$portInUse = netstat -ano | Select-String ":11435.*LISTENING"
if ($portInUse) {
    Write-Info 'Found process on port 11435, stopping...'
    foreach ($line in $portInUse) {
        $parts = $line.Trim() -split '\s+'
        $ppid = $parts[4]
        Write-Info "Stopping PID $ppid..."
        Stop-Process -Id ([int]$ppid) -Force -ErrorAction SilentlyContinue
        Write-Ok "Stopped PID $ppid"
    }
} else {
    Write-Info 'No process listening on port 11435'
}

# ---- Step 3: Fallback - stop by process name ----
Write-Host ''
Write-Host '[Step 3] Checking llama-server processes...'

$llamaProcs = Get-Process -Name 'llama-server' -ErrorAction SilentlyContinue
if ($llamaProcs) {
    Write-Info "Found $($llamaProcs.Count) llama-server process(es), stopping all..."
    $llamaProcs | Stop-Process -Force
    Write-Ok 'All llama-server processes stopped'
} else {
    Write-Info 'No running llama-server process found'
}

# ---- Step 4: Verify ----
Write-Host ''
Write-Host '[Step 4] Verifying port 11435 is released...'
Start-Sleep -Seconds 2

$stillListening = netstat -ano | Select-String ":11435.*LISTENING"
if ($stillListening) {
    Write-Err 'Port 11435 still in use, forcing release...'
    foreach ($line in $stillListening) {
        $parts = $line.Trim() -split '\s+'
        Stop-Process -Id ([int]$parts[4]) -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 1

    $stillListening2 = netstat -ano | Select-String ":11435.*LISTENING"
    if ($stillListening2) {
        Write-Err 'Cannot release port 11435, please handle manually'
        pause
        exit 1
    }
}

Write-Host ''
Write-Ok 'Qwen3-Reranker service has been stopped'
Write-Info 'Port 11435 is now free'
Write-Host ''
Write-Info 'To restart, run: start-qwen3-reranker.bat'
Write-Host '==========================================================='
pause
exit 0
