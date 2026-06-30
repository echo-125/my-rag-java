<#
.SYNOPSIS
    Ollama Rerank 服务 - 可移植版（无硬编码路径）
.DESCRIPTION
    自动检测 Ollama 安装路径和模型位置，在任何装有 Ollama 的 Windows 机器上运行
    不需要修改任何路径配置
.EXAMPLE
    .\start-rerank.ps1                           # 自动检测并使用默认模型
    .\start-rerank.ps1 -Model bge                 # 指定模型
    .\start-rerank.ps1 -Model all                 # 对比所有已安装的重排模型
    .\start-rerank.ps1 -ListModels                # 列出所有已安装模型
    .\start-rerank.ps1 -Port 11436                # 自定义端口
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory=$false)]
    [ValidateSet("qwen3", "bge", "audit", "all")]
    [string]$Model = "qwen3",

    [Parameter(Mandatory=$false)]
    [int]$Port = 11435,

    [Parameter(Mandatory=$false)]
    [string]$HostAddress = "127.0.0.1",

    [Parameter(Mandatory=$false)]
    [int]$Threads = -1,

    [Parameter(Mandatory=$false)]
    [switch]$NoFlashAttn,

    [Parameter(Mandatory=$false)]
    [switch]$KeepRunning,

    [Parameter(Mandatory=$false)]
    [switch]$ListModels
)

# ============================================================
# 输出辅助函数（必须在使用前定义）
# ============================================================

function Write-Header {
    param([string]$Text)
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor Cyan
    Write-Host ("  $Text") -ForegroundColor Cyan
    Write-Host ("=" * 70) -ForegroundColor Cyan
    Write-Host ""
}

function Write-Step { param([string]$Text) Write-Host ("[步骤] $Text") -ForegroundColor Yellow }
function Write-Success { param([string]$Text) Write-Host ("[成功] $Text") -ForegroundColor Green }
function Write-ErrorMsg { param([string]$Text) Write-Host ("[错误] $Text") -ForegroundColor Red }
function Write-Info { param([string]$Text) Write-Host ("[信息] $Text") -ForegroundColor Gray }

# ============================================================
# 路径自动检测函数
# ============================================================

function Find-OllamaInstallDir {
    # 方法 1: 从 PATH 找 ollama.exe
    $ollamaExe = Get-Command ollama -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
    if ($ollamaExe) {
        return Split-Path -Parent $ollamaExe
    }

    # 方法 2: 从运行中的进程找
    $ollamaProcess = Get-Process -Name "ollama*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($ollamaProcess) {
        return Split-Path -Parent $ollamaProcess.Path
    }

    # 方法 3: 常见安装位置
    $commonPaths = @(
        "$env:ProgramFiles\Ollama",
        "${env:ProgramFiles(x86)}\Ollama",
        "$env:LOCALAPPDATA\Ollama",
        "$env:USERPROFILE\ollama",
        "E:\ollama",
        "C:\ollama"
    )
    foreach ($path in $commonPaths) {
        if (Test-Path "$path\ollama.exe") {
            return $path
        }
    }

    return $null
}

function Find-LlamaServer {
    param([string]$OllamaDir)

    # Ollama 自带的 llama-server 路径
    $candidates = @(
        "$OllamaDir\lib\ollama\llama-server.exe",
        "$OllamaDir\llama-server.exe"
    )

    foreach ($path in $candidates) {
        if (Test-Path $path) {
            return $path
        }
    }

    # 尝试 lib 目录下搜索
    $libDir = "$OllamaDir\lib"
    if (Test-Path $libDir) {
        $found = Get-ChildItem -Path $libDir -Recurse -Filter "llama-server.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) {
            return $found.FullName
        }
    }

    return $null
}

function Find-ModelGgufPath {
    param([string]$ModelName)

    $modelfile = ollama show $ModelName --modelfile 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }

    foreach ($line in $modelfile) {
        if ($line -match '^FROM\s+(.+)$') {
            $path = $Matches[1].Trim()
            $path = $path -replace '^["'']|["'']$', ''
            if (Test-Path $path) {
                return $path
            }
            return $path
        }
    }
    return $null
}

function Get-InstalledRerankModels {
    $models = @()
    $output = ollama list 2>$null
    foreach ($line in $output) {
        if ($line -match '(?i)rerank') {
            # ollama list 格式: NAME ID SIZE MODIFIED
            $parts = $line.Trim() -split '\s+'
            $name = $parts[0]
            $size = if ($parts.Count -ge 4) { "$($parts[2]) $($parts[3])" } else { "unknown" }
            $ggufPath = Find-ModelGgufPath -ModelName $name
            $models += [PSCustomObject]@{
                Name     = $name
                Size     = $size
                GGUFPath = $ggufPath
            }
        }
    }
    return $models
}

# ============================================================
# 服务管理函数
# ============================================================

function Test-PortAvailable {
    param([int]$Port)
    try {
        $tcp = New-Object System.Net.Sockets.TcpClient
        $tcp.Connect($HostAddress, $Port)
        $tcp.Close()
        return $false
    } catch { return $true }
}

function Wait-ServiceReady {
    param([string]$BaseUrl, [int]$TimeoutSec = 120)
    Write-Step "等待服务就绪..."
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $body = '{"query":"ping","documents":["ping"],"top_n":1}'
            Invoke-RestMethod -Uri "$BaseUrl/v1/rerank" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 5 -ErrorAction Stop | Out-Null
            Write-Success "服务已就绪!"
            return $true
        } catch {
            $code = $_.Exception.Response.StatusCode.value__
            if ($code -and $code -ne 404) {
                Start-Sleep -Seconds 3
                continue
            }
        }
        $remaining = [math]::Round(($deadline - (Get-Date)).TotalSeconds, 1)
        Write-Info ("  等待模型加载... (剩余 {0}s)" -f $remaining)
        Start-Sleep -Seconds 3
    }
    Write-ErrorMsg "服务在 ${TimeoutSec}s 内未就绪"
    return $false
}

function Stop-ServiceOnPort {
    param([int]$Port)
    Write-Step "停止端口 $Port 上的服务..."
    try {
        $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($connections) {
            foreach ($conn in $connections) {
                $pid = $conn.OwningProcess
                try {
                    $proc = Get-Process -Id $pid -ErrorAction Stop
                    Write-Info ("  停止: {0} (PID: {1})" -f $proc.ProcessName, $pid)
                    Stop-Process -Id $pid -Force -ErrorAction Stop
                } catch { }
            }
            Start-Sleep -Seconds 2
        }
    } catch { }
    if (Test-PortAvailable -Port $Port) {
        Write-Success "端口 $Port 已清理"
    } else {
        Get-Process -Name "llama-server*" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
}

function Start-RerankServer {
    param(
        [Parameter(Mandatory)][string]$GgufPath,
        [Parameter(Mandatory)][string]$ModelLabel,
        [Parameter(Mandatory)][int]$Port,
        [string]$HostAddr = "127.0.0.1",
        [int]$CpuThreads = -1,
        [switch]$DisableFlashAttn
    )

    if (-not (Test-Path $GgufPath)) {
        Write-ErrorMsg ("模型文件不存在: {0}" -f $GgufPath)
        return $null
    }

    if (-not (Test-PortAvailable -Port $Port)) {
        Write-Info "端口 $Port 被占用, 清理中..."
        Stop-ServiceOnPort -Port $Port
        if (-not (Test-PortAvailable -Port $Port)) {
            Write-ErrorMsg "无法释放端口 $Port"
            return $null
        }
    }

    Write-Step ("启动: {0}" -f $ModelLabel)
    Write-Info ("  模型: {0}" -f $GgufPath)
    Write-Info ("  地址: http://{0}:{1}" -f $HostAddr, $Port)

    $arguments = @(
        "--rerank"
        "--model", "`"$GgufPath`""
        "--port", "$Port"
        "--host", $HostAddr
        "--threads", "$CpuThreads"
        "--api-prefix", "/v1"
        "--flash-attn", "auto"
    )
    if ($DisableFlashAttn) {
        $arguments[-2] = "--flash-attn"
        $arguments[-1] = "off"
    }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:LlamaServer
    $psi.Arguments = $arguments -join " "
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.Environment["LLAMA_ARG_RERANKING"] = "1"

    try {
        $proc = [System.Diagnostics.Process]::Start($psi)
        Write-Info ("  进程 PID: {0}" -f $proc.Id)
    } catch {
        Write-ErrorMsg ("启动失败: {0}" -f $_)
        return $null
    }

    $baseUrl = "http://${HostAddress}:${Port}"
    if (-not (Wait-ServiceReady -BaseUrl $baseUrl -TimeoutSec 120)) {
        Write-ErrorMsg "服务启动失败"
        while (-not $proc.StandardOutput.EndOfStream) { Write-Info $proc.StandardOutput.ReadLine() }
        while (-not $proc.StandardError.EndOfStream) { Write-ErrorMsg $proc.StandardError.ReadLine() }
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        return $null
    }

    Write-Success ("服务就绪: {0}" -f $baseUrl)
    Write-Info ""
    return $proc
}

function Invoke-RerankApi {
    param(
        [Parameter(Mandatory)][string]$Query,
        [Parameter(Mandatory)][string[]]$Documents,
        [int]$TopN = $Documents.Length,
        [string]$ApiBase
    )

    $payload = @{
        model     = ""
        query     = $Query
        documents = $Documents
        top_n     = $TopN
    } | ConvertTo-Json -Depth 3 -Compress

    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $response = Invoke-RestMethod -Uri "$ApiBase/v1/rerank" -Method Post -Body $payload -ContentType "application/json; charset=utf-8" -TimeoutSec 120
        $sw.Stop()
        return @{ Response = $response; Elapsed = $sw.ElapsedMilliseconds }
    } catch {
        Write-ErrorMsg ("API 调用失败: {0}" -f $_.Exception.Message)
        return $null
    }
}

function Show-Results {
    param(
        [string]$Title,
        [string]$Query,
        [object]$Results,
        [string[]]$Documents,
        [int]$ElapsedMs = 0
    )
    Write-Host ""
    Write-Host $Title -ForegroundColor Magenta
    Write-Host ("-" * 70)
    Write-Host ("  查询: {0}" -f $Query) -ForegroundColor White
    if ($ElapsedMs -gt 0) {
        Write-Host ("  耗时: {0}ms" -f $ElapsedMs) -ForegroundColor DarkGray
    }
    Write-Host ""

    if (-not $Results.results) { Write-ErrorMsg "  无结果"; return }

    $rank = 1
    foreach ($r in $Results.results) {
        $idx = $r.index
        $score = $r.relevance_score
        $doc = if ($idx -lt $Documents.Length) { $Documents[$idx] } else { "[超出范围]" }
        $color = if ($score -ge 0.8) { "Green" } elseif ($score -ge 0.3) { "Yellow" } else { "DarkGray" }
        $barLen = [math]::Floor([Math]::Max(0, [Math]::Min(1, $score)) * 24)
        $bar = ("█" * $barLen) + ("░" * (24 - $barLen))
        Write-Host ("  {0}. {1} {2,8:F4}  {3}" -f $rank.ToString().PadLeft(2), $bar, $score, $doc) -ForegroundColor $color
        $rank++
    }
    Write-Host ("-" * 70)
    Write-Host ""
}

function Test-Model {
    param(
        [Parameter(Mandatory)][string]$GgufPath,
        [Parameter(Mandatory)][string]$ModelLabel,
        [int]$Port,
        [string]$HostAddr
    )

    $baseUrl = "http://${HostAddr}:${Port}"

    $service = Start-RerankServer -GgufPath $GgufPath -ModelLabel $ModelLabel -Port $Port -HostAddr $HostAddr -CpuThreads $Threads -DisableFlashAttn:$NoFlashAttn
    if (-not $service) { return $null }

    $script:ServiceProcess = $service

    $cleanup = {
        if ($script:ServiceProcess -and -not $script:ServiceProcess.HasExited) {
            Stop-Process -Id $script:ServiceProcess.Id -Force -ErrorAction SilentlyContinue
        }
    }
    Register-EngineEvent -SourceIdentifier PowerShell.Exiting -Action $cleanup -ErrorAction SilentlyContinue | Out-Null

    $query = "什么是机器学习？"
    $documents = @(
        "机器学习是人工智能的一个分支，通过数据训练模型。",
        "深度学习是机器学习的一种，使用神经网络。",
        "Python 是一种编程语言，适合数据处理。",
        "今天天气很好，适合户外运动。",
        "神经网络可以模拟人脑的工作方式。",
        "机器学习算法包括监督学习、无监督学习和强化学习。"
    )

    Write-Info ("  查询: {0}" -f $query)
    Write-Info ("  文档: {0} 条" -f $documents.Count)
    Write-Host ""

    $result = Invoke-RerankApi -Query $query -Documents $documents -ApiBase $baseUrl
    if ($result) {
        Show-Results -Title "[$ModelLabel] 重排结果" -Query $query -Results $result.Response -Documents $documents -ElapsedMs $result.Elapsed
        Write-Host "原始 JSON:" -ForegroundColor DarkGray
        Write-Host ($result.Response | ConvertTo-Json -Depth 5) -ForegroundColor DarkGray
        Write-Host ""
    }

    Stop-ServiceOnPort -Port $Port
    $script:ServiceProcess = $null
    return $result
}

# ============================================================
# 自动检测与主流程
# ============================================================

Write-Host ""
Write-Host ("=" * 70) -ForegroundColor Cyan
Write-Host "  Ollama Rerank 服务 - 自动检测模式" -ForegroundColor Cyan
Write-Host ("=" * 70) -ForegroundColor Cyan
Write-Host ""

# 检测 Ollama 安装
Write-Step "检测 Ollama 安装..."
$ollamaDir = Find-OllamaInstallDir
if (-not $ollamaDir) {
    Write-ErrorMsg "未找到 Ollama 安装"
    Write-Info "请确认 Ollama 已安装并在 PATH 中"
    Write-Info "下载地址: https://ollama.com"
    exit 1
}
Write-Success "Ollama 目录: $ollamaDir"

# 检测 llama-server
Write-Step "检测 llama-server..."
$script:LlamaServer = Find-LlamaServer -OllamaDir $ollamaDir
if (-not $script:LlamaServer) {
    Write-ErrorMsg "未找到 llama-server.exe"
    Write-Info "Ollama 安装可能不完整, 请重新安装"
    exit 1
}
Write-Success "llama-server: $script:LlamaServer"

# 扫描已安装的重排模型
Write-Step "扫描已安装的重排模型..."
$rerankModels = Get-InstalledRerankModels

if ($rerankModels.Count -eq 0) {
    Write-ErrorMsg "未找到已安装的重排模型"
    Write-Info "请先拉取模型, 例如:"
    Write-Info "  ollama pull B-A-M-N/Qwen3-Reranker-0.6B-fp16:latest"
    Write-Info "  ollama pull dengcao/bge-reranker-v2-m3:latest"
    exit 1
}

Write-Success "找到 $($rerankModels.Count) 个重排模型:"
foreach ($m in $rerankModels) {
    $status = if ($m.GGUFPath) { "文件存在" } else { "文件未找到!" }
    Write-Info ("  {0} ({1}) - {2}" -f $m.Name, $m.Size, $status)
    if (-not $m.GGUFPath) {
        Write-ErrorMsg ("    GGUF 路径: {0}" -f $m.GGUFPath)
    }
}
Write-Host ""

# -ListModels 模式: 只列出模型, 不启动服务
if ($ListModels) {
    Write-Header "所有已安装模型"
    $allModels = ollama list 2>$null
    foreach ($m in $allModels) {
        Write-Host "  $m" -ForegroundColor Gray
    }
    exit 0
}

# 确定目标模型
$targetModels = @()
if ($Model -eq "all") {
    $targetModels = $rerankModels
} else {
    # 模糊匹配
    $match = $rerankModels | Where-Object {
        $_.Name -like "*$Model*" -or $_.Name -like "*$($Model.ToLower())*"
    }
    if ($match) {
        $targetModels = @($match)
    } else {
        # 尝试直接查找
        $ggufPath = Find-ModelGgufPath -ModelName $Model
        if ($ggufPath) {
            $targetModels = @([PSCustomObject]@{
                Name     = $Model
                Size     = "unknown"
                GGUFPath = $ggufPath
            })
        } else {
            Write-ErrorMsg ("未找到匹配的模型: {0}" -f $Model)
            Write-Info "已安装的重排模型:"
            foreach ($m in $rerankModels) {
                Write-Info ("  {0}" -f $m.Name)
            }
            exit 1
        }
    }
}

if ($targetModels.Count -eq 0) {
    Write-ErrorMsg "没有可用的重排模型"
    exit 1
}

# ============================================================
# 执行测试
# ============================================================

$API_BASE = "http://${HostAddress}:${Port}"

if ($targetModels.Count -gt 1) {
    # 多模型对比
    Write-Header "多模型对比测试"
    $query = "什么是机器学习？"
    $documents = @(
        "机器学习是人工智能的一个分支，通过数据训练模型。",
        "深度学习是机器学习的一种，使用神经网络。",
        "Python 是一种编程语言，适合数据处理。",
        "今天天气很好，适合户外运动。",
        "神经网络可以模拟人脑的工作方式。",
        "机器学习算法包括监督学习、无监督学习和强化学习。"
    )
    $allResults = @{}

    foreach ($m in $targetModels) {
        $label = $m.Name.Split('/')[-1].Split(':')[0]
        $result = Test-Model -GgufPath $m.GGUFPath -ModelLabel $label -Port $Port -HostAddr $HostAddress
        if ($result) {
            $allResults[$label] = $result
        }
    }

    # 对比汇总
    if ($allResults.Count -gt 1) {
        Write-Header "对比汇总"
        Write-Host ("  查询: {0}" -f $query) -ForegroundColor White
        Write-Host ""
        foreach ($label in $allResults.Keys) {
            $r = $allResults[$label]
            Write-Host ("--- {0} ({1}ms) ---" -f $label, $r.Elapsed) -ForegroundColor Cyan
            $rank = 1
            foreach ($result in $r.Response.results) {
                $idx = $result.index
                $score = $result.relevance_score
                $docText = $documents[$idx]
                $color = if ($score -ge 0.8) { "Green" } elseif ($score -ge 0.3) { "Yellow" } else { "DarkGray" }
                Write-Host ("  {0}. [{1}] {2:F4} | {3}" -f $rank, $idx, $score, $docText) -ForegroundColor $color
                $rank++
            }
            Write-Host ""
        }
    }
    Stop-ServiceOnPort -Port $Port
    Write-Header "完成"
} else {
    # 单模型
    $m = $targetModels[0]
    $label = $m.Name.Split('/')[-1].Split(':')[0]
    Test-Model -GgufPath $m.GGUFPath -ModelLabel $label -Port $Port -HostAddr $HostAddress | Out-Null

    if ($KeepRunning) {
        Write-Success "服务保持运行: $API_BASE"
        Write-Host ""
        Write-Host "按 Enter 停止..." -ForegroundColor Yellow
        $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
        Stop-ServiceOnPort -Port $Port
    }
    Write-Header "完成"
}
