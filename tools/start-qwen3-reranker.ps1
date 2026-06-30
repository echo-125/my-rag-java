$ErrorActionPreference = 'Stop'

$MODEL_NAME = 'B-A-M-N/Qwen3-Reranker-0.6B-fp16:latest'
$PORT = 11435
$BIND_HOST = '127.0.0.1'
$CTX_SIZE = 8192
$PID_FILE = Join-Path $env:TEMP 'llama-rerank.pid'

function Write-Info($msg) { Write-Host "  $msg" }
function Write-Err($msg) { Write-Host "  [ERROR] $msg" -ForegroundColor Red }
function Write-Ok($msg) { Write-Host "  [OK] $msg" -ForegroundColor Green }

# ---- [1/5] Find Ollama ----
Write-Host '[1/5] Locating Ollama installation...'

$OLLAMA_DIR = $null
$ollamaCmd = Get-Command ollama -ErrorAction SilentlyContinue
if ($ollamaCmd) {
    $OLLAMA_DIR = Split-Path $ollamaCmd.Source -Parent
}
if (-not $OLLAMA_DIR) {
    $p1 = 'C:\Program Files\Ollama\ollama.exe'
    if (Test-Path $p1) { $OLLAMA_DIR = Split-Path $p1 -Parent }
}
if (-not $OLLAMA_DIR) {
    $p2 = 'E:\ollama\ollama.exe'
    if (Test-Path $p2) { $OLLAMA_DIR = Split-Path $p2 -Parent }
}
if (-not $OLLAMA_DIR) {
    $p3 = Join-Path $env:LOCALAPPDATA 'Ollama\ollama.exe'
    if (Test-Path $p3) { $OLLAMA_DIR = Split-Path $p3 -Parent }
}

if (-not $OLLAMA_DIR -or -not (Test-Path (Join-Path $OLLAMA_DIR 'ollama.exe'))) {
    Write-Err 'Ollama installation not found'
    exit 1
}
Write-Info "Ollama directory: $OLLAMA_DIR"

# ---- [2/5] Find llama-server ----
Write-Host '[2/5] Locating llama-server.exe...'

$LLAMA_SERVER = $null
$lsp1 = Join-Path $OLLAMA_DIR 'lib\ollama\llama-server.exe'
if (Test-Path $lsp1) { $LLAMA_SERVER = $lsp1 }
if (-not $LLAMA_SERVER) {
    $lsp2 = Join-Path $OLLAMA_DIR 'llama-server.exe'
    if (Test-Path $lsp2) { $LLAMA_SERVER = $lsp2 }
}

if (-not $LLAMA_SERVER) {
    Write-Err 'llama-server.exe not found'
    exit 1
}
Write-Info "llama-server: $LLAMA_SERVER"

# ---- [3/5] Ensure Ollama service is running ----
Write-Host '[3/5] Checking Ollama service...'

$ollamaRunning = $false
try {
    $null = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -Method Get -TimeoutSec 3 -ErrorAction Stop
    $ollamaRunning = $true
} catch { $ollamaRunning = $false }

if (-not $ollamaRunning) {
    Write-Info 'Ollama not running, starting...'
    Start-Process -FilePath (Join-Path $OLLAMA_DIR 'ollama.exe') -WindowStyle Hidden
    Start-Sleep -Seconds 5
}

# ---- [4/5] Resolve model GGUF path ----
Write-Host '[4/5] Resolving model GGUF path...'
Write-Info "Model: $MODEL_NAME"

$modelfileOutput = ollama show $MODEL_NAME --modelfile 2>&1
$fromLine = $modelfileOutput | Select-String '^FROM\s+(.+)'
if (-not $fromLine) {
    Write-Err "Model not pulled. Run: ollama pull $MODEL_NAME"
    exit 1
}

$modelPath = $fromLine.Matches.Groups[1].Value.Trim()
if ($modelPath -match '^sha256-') {
    $home = $env:OLLAMA_MODELS
    if (-not $home) { $home = Join-Path $env:USERPROFILE '.ollama\models' }
    $modelPath = Join-Path $home 'blobs' $modelPath
}

if (-not (Test-Path $modelPath)) {
    Write-Err "Model file not found: $modelPath"
    exit 1
}
Write-Info "GGUF path: $modelPath"

# ---- [5/5] Start rerank service in background ----
Write-Host '[5/5] Starting rerank service...'

$portInUse = netstat -ano | Select-String ":${PORT}.*LISTENING"
if ($portInUse) {
    Write-Info "Port $PORT in use, stopping old process..."
    foreach ($line in $portInUse) {
        $parts = $line.Trim() -split '\s+'
        Stop-Process -Id ([int]$parts[4]) -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 2
}

$a1 = '--rerank'
$a2 = '--model'
$a3 = $modelPath
$a4 = '--port'
$a5 = "$PORT"
$a6 = '--host'
$a7 = $BIND_HOST
$a8 = '--ctx-size'
$a9 = "$CTX_SIZE"
$a10 = '--no-webui'

$proc = Start-Process -FilePath $LLAMA_SERVER -ArgumentList $a1,$a2,$a3,$a4,$a5,$a6,$a7,$a8,$a9,$a10 -PassThru -WindowStyle Normal
Start-Sleep -Seconds 1

if ($proc.HasExited) {
    Write-Err 'llama-server exited immediately'
    exit 1
}

$proc.Id | Out-File $PID_FILE -NoNewline
Write-Host ''
Write-Host '============================================================'
Write-Ok "Service started (PID: $($proc.Id))"
Write-Host '============================================================'
Write-Host ''
Write-Info "URL: http://${BIND_HOST}:${PORT}/v1/rerank"
Write-Info 'To stop: stop-qwen3-reranker.bat'
Write-Host ''

exit 0
