param()

$PORT = 11435
$URL = "http://127.0.0.1:${PORT}/v1/rerank"

function Write-Info($msg) { Write-Host "  $msg" }
function Write-Err($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red }
function Write-Ok($msg) { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Pass($msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }

# ---- Step 1: Check service ----
Write-Host '[Step 1] Checking if service is running on port' $PORT '...'

$listening = netstat -ano | Select-String ":${PORT}.*LISTENING"
if ($listening) {
    Write-Ok "Service is listening on port $PORT"
} else {
    Write-Err "Service is NOT running on port $PORT"
    Write-Info '  Run start-qwen3-reranker.bat first'
    pause
    exit 1
}
Write-Host ''

# ---- Step 2: Basic rerank ----
Write-Host '[Step 2] Test 1 - Basic rerank (query vs documents)...'

$body1 = '{"query":"Apple","documents":["apple","banana","fruit","vegetable"]}'
try {
    $r1 = Invoke-RestMethod -Uri $URL -Method Post -Body $body1 -ContentType 'application/json' -TimeoutSec 10
    Write-Info 'Response received, results:'
    foreach ($res in $r1.results) {
        Write-Host ("    doc[" + $res.index + "] score=" + $res.relevance_score)
    }
    Write-Pass 'Test 1'
} catch {
    Write-Err "Test 1 failed: $($_.Exception.Message)"
    pause
    exit 1
}
Write-Host ''

# ---- Step 3: Top-N filtering ----
Write-Host '[Step 3] Test 2 - Top-N filtering (top_n=2)...'

$body2 = '{"query":"Java programming","documents":["Java is a programming language","Python is popular","Java Spring Boot framework","Cooking recipes"],"top_n":2}'
try {
    $r2 = Invoke-RestMethod -Uri $URL -Method Post -Body $body2 -ContentType 'application/json' -TimeoutSec 10
    Write-Info "Got $($r2.results.Count) results (expected 2):"
    foreach ($res in $r2.results) {
        Write-Host ("    doc[" + $res.index + "] score=" + $res.relevance_score)
    }
    Write-Pass 'Test 2'
} catch {
    Write-Err "Test 2 failed: $($_.Exception.Message)"
    pause
    exit 1
}
Write-Host ''

# ---- Step 4: Chinese query ----
Write-Host '[Step 4] Test 3 - Chinese query...'

$body3 = '{"query":"什么是RAG检索增强生成","documents":["RAG是一种结合检索和生成的技术","Spring Boot是一个Java框架","RAG可以提升大模型回答质量","Docker用于容器化部署"]}'
try {
    $r3 = Invoke-RestMethod -Uri $URL -Method Post -Body $body3 -ContentType 'application/json' -TimeoutSec 10
    Write-Info 'Response received, results:'
    foreach ($res in $r3.results) {
        Write-Host ("    doc[" + $res.index + "] score=" + $res.relevance_score)
    }
    Write-Pass 'Test 3'
} catch {
    Write-Err "Test 3 failed: $($_.Exception.Message)"
    pause
    exit 1
}
Write-Host ''

# ---- Summary ----
Write-Host '============================================================'
Write-Host '  All tests passed!'
Write-Host '============================================================'
Write-Host ''
Write-Info "Service URL: $URL"
Write-Info 'Model: Qwen3-Reranker-0.6B'
Write-Host ''
Write-Info 'To stop service: stop-qwen3-reranker.bat'
Write-Host '============================================================'
pause
exit 0
