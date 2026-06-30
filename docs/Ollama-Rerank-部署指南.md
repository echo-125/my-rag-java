# Ollama Rerank 服务部署指南

> 在 Windows 上通过 Ollama 自带的 `llama-server` 启动重排（Rerank）服务，为 RAG 系统提供文档重排序能力。
> 
> 参考：[AuditAid/Qwen3_Reranker](https://ollama.com/AuditAid/Qwen3_Reranker) | [audit-tool-skills/Rerank](https://github.com/AuditAIH/audit-tool-skills/tree/main/Rerank)

---

## 一、环境要求

| 项目 | 要求 |
|------|------|
| 操作系统 | Windows 10/11 / Windows Server 2019+ |
| Ollama | 已安装并运行（端口 11434） |
| PowerShell | 5.1 或更高版本 |
| 网络 | 可访问 Ollama 模型库（或已本地缓存模型） |

---

## 二、安装重排模型

通过 Ollama 拉取以下任一模型（推荐 Qwen3-Reranker）：

```powershell
# 方案 A：Qwen3-Reranker（推荐，排序质量高）
ollama pull B-A-M-N/Qwen3-Reranker-0.6B-fp16:latest

# 方案 B：bge-reranker-v2-m3（排序质量相当，体积略小）
ollama pull dengcao/bge-reranker-v2-m3:latest

# 方案 C：AuditAid Q8 量化版（体积最小，607MB）
ollama pull AuditAid/Qwen3_Reranker:0.6B_Q8
```

---

## 三、定位关键文件

### 1. 找到 llama-server.exe

Ollama 安装时自带 `llama-server.exe`，无需单独编译：

```powershell
# 自动查找（PowerShell）
$llamaServer = Get-ChildItem -Path (Split-Path (Get-Command ollama).Source) -Recurse -Filter "llama-server.exe" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName

Write-Host "llama-server.exe 路径: $llamaServer"
```

**常见位置：**
- 默认安装：`C:\Program Files\Ollama\lib\ollama\llama-server.exe`
- 自定义安装：`<Ollama安装目录>\lib\ollama\llama-server.exe`

### 2. 找到模型 GGUF 文件

```powershell
# 获取指定模型的 GGUF 文件路径
function Get-ModelGGUFPath {
    param([string]$ModelName)

    # 获取模型信息
    $modelInfo = ollama show $ModelName --modelfile

    # 提取 FROM 行中的文件路径
    $fromLine = $modelInfo | Select-String "^FROM\s+(.+)" | ForEach-Object { $_.Matches.Groups[1].Value.Trim() }

    if ($fromLine -match "^sha256-") {
        # 相对路径，需要拼接 Ollama 模型目录
        $ollamaHome = if ($env:OLLAMA_MODELS) { $env:OLLAMA_MODELS } else { "$env:USERPROFILE\.ollama\models" }
        return Join-Path $ollamaHome "blobs" $fromLine
    } else {
        return $fromLine
    }
}

# 使用示例
$modelPath = Get-ModelGGUFPath -ModelName "B-A-M-N/Qwen3-Reranker-0.6B-fp16:latest"
Write-Host "模型路径: $modelPath"
```

---

## 四、启动 Rerank 服务

### 手动启动

```powershell
# 配置变量（按需修改）
$llamaServer = "C:\Program Files\Ollama\lib\ollama\llama-server.exe"  # 步骤三获取的实际路径
$modelPath = Get-ModelGGUFPath -ModelName "B-A-M-N/Qwen3-Reranker-0.6B-fp16:latest"
$port = 11435
$hostAddr = "127.0.0.1"

# 启动服务（在新窗口中运行，保持后台）
Start-Process -FilePath $llamaServer `
    -ArgumentList "--rerank", "--model", $modelPath, "--port", $port, "--host", $hostAddr, "--threads", "-1", "--api-prefix", "/v1", "--flash-attn", "auto", "--no-webui", "--ctx-size", "8192" `
    -WindowStyle Minimized

Write-Host "Rerank 服务已启动: http://$hostAddr`:$port/v1/rerank"
```

### 服务启动参数说明

| 参数 | 说明 |
|------|------|
| `--rerank` | **必需**，启用重排端点 |
| `--model` | **必需**，GGUF 模型文件的完整路径 |
| `--port` | 服务端口（默认 8080，建议 11435） |
| `--host` | 监听地址（`127.0.0.1` 仅本地，`0.0.0.0` 允许远程） |
| `--api-prefix` | API 前缀，设为 `/v1` 兼容 OpenAI 格式 |
| `--flash-attn` | Flash Attention 加速（`auto` 自动选择） |
| `--threads` | 线程数（`-1` 自动） |
| `--ctx-size` | 上下文长度（默认 4096，可设为 8192） |
| `--no-webui` | 禁用 Web UI，仅保留 API |

---

## 五、验证服务状态

```powershell
# 检查服务是否运行
Invoke-RestMethod -Uri "http://127.0.0.1:11435/health" -Method Get

# 预期响应: {"status":"ok"}
```

---

## 六、API 调用

### 请求格式

```powershell
$documents = @(
    "机器学习是人工智能的一个分支，通过数据训练模型。",
    "深度学习是机器学习的一种，使用神经网络。",
    "Python 是一种编程语言，适合数据处理。",
    "今天天气很好，适合户外运动。"
)

$body = @{
    model       = ""           # 模型名称（可为空，服务已绑定模型）
    query       = "什么是机器学习？"
    documents   = $documents
    top_n       = 4            # 返回前 N 个结果（可选，默认全部）
} | ConvertTo-Json -Depth 3

$response = Invoke-RestMethod `
    -Uri "http://127.0.0.1:11435/v1/rerank" `
    -Method Post `
    -Body $body `
    -ContentType "application/json"

# 输出结果
$response.results | Sort-Object relevance_score -Descending | ForEach-Object {
    $idx = $_.index
    $score = $_.relevance_score
    Write-Host ("[{0}] 分数: {1:N4} | {2}" -f $idx, $score, $documents[$idx])
}
```

### 响应格式

```json
{
  "model": "",
  "object": "list",
  "usage": {
    "prompt_tokens": 529,
    "total_tokens": 529
  },
  "results": [
    {"index": 0, "relevance_score": 0.9997},
    {"index": 1, "relevance_score": 0.9560},
    {"index": 2, "relevance_score": 0.0020},
    {"index": 3, "relevance_score": 0.0001}
  ]
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `index` | int | 文档在原始列表中的索引 |
| `relevance_score` | float | 相关性分数（0~1，越高越相关） |
| `prompt_tokens` | int | 输入 token 数 |
| `total_tokens` | int | 总 token 数 |

---

## 七、完整测试脚本

保存为 `rerank-test.ps1`：

```powershell
<#
.SYNOPSIS
    Rerank API 测试脚本
.DESCRIPTION
    测试 Ollama llama-server 重排服务
.PARAMETER Url
    Rerank 服务地址（默认 http://127.0.0.1:11435）
#>
param(
    [string]$Url = "http://127.0.0.1:11435"
)

# 测试数据
$query = "什么是机器学习？"
$documents = @(
    "机器学习是人工智能的一个分支，通过数据训练模型。",
    "深度学习是机器学习的一种，使用神经网络。",
    "Python 是一种编程语言，适合数据处理。",
    "今天天气很好，适合户外运动。",
    "神经网络可以模拟人脑的工作方式。",
    "机器学习算法包括监督学习、无监督学习和强化学习。"
)

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  Ollama Rerank API 测试" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan

# 检查服务
Write-Host "[步骤] 检查服务状态: $Url" -ForegroundColor Gray
try {
    $health = Invoke-RestMethod -Uri "$Url/health" -Method Get -TimeoutSec 5
    Write-Host "[成功] 服务运行中 (状态: $($health | ConvertTo-Json -Compress))" -ForegroundColor Green
} catch {
    Write-Host "[失败] 服务未运行，请先启动 llama-server --rerank" -ForegroundColor Red
    exit 1
}

# 发送请求
Write-Host "`n======================================================================" -ForegroundColor Cyan
Write-Host "  执行重排" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host "  查询: $query" -ForegroundColor Yellow
Write-Host "  文档: $($documents.Count) 条" -ForegroundColor Yellow

$body = @{
    model     = ""
    query     = $query
    documents = $documents
    top_n     = $documents.Count
} | ConvertTo-Json -Depth 3

$start = Get-Date
Write-Host "`n[步骤] 发送请求到 $Url/v1/rerank" -ForegroundColor Gray

try {
    $response = Invoke-RestMethod -Uri "$Url/v1/rerank" -Method Post -Body $body -ContentType "application/json"
    $elapsed = ((Get-Date) - $start).TotalMilliseconds

    Write-Host "[成功] 响应成功 (${elapsed}ms)" -ForegroundColor Green
    Write-Host "`n  查询: $query" -ForegroundColor White
    Write-Host "  耗时: ${elapsed}ms" -ForegroundColor Gray
    Write-Host "  Token: prompt=$($response.usage.prompt_tokens), total=$($response.usage.total_tokens)" -ForegroundColor Gray
    Write-Host ""

    # 排序并可视化
    $sorted = $response.results | Sort-Object relevance_score -Descending
    $maxScore = ($sorted | Measure-Object -Property relevance_score -Maximum).Maximum

    foreach ($item in $sorted) {
        $idx = $item.index
        $score = $item.relevance_score
        $doc = $documents[$idx]
        $barLen = [Math]::Round($score / $maxScore * 24)
        $bar = "█" * $barLen + "░" * (24 - $barLen)
        $color = if ($score -gt 0.5) { "Green" } elseif ($score -gt 0.01) { "Yellow" } else { "Gray" }
        Write-Host ("{0,2}. {1} {2,7:F4}  {3}" -f ($sorted.IndexOf($item) + 1), $bar, $score, $doc) -ForegroundColor $color
    }

    Write-Host "`n[成功] 测试完成!" -ForegroundColor Green

} catch {
    Write-Host "[失败] 请求错误: $_" -ForegroundColor Red
    exit 1
}
```

---

## 八、多模型对比脚本

保存为 `rerank-compare.ps1`，用于对比不同重排模型效果：

```powershell
<#
.SYNOPSIS
    多模型重排对比测试
#>
param(
    [string]$LlamaServer = (Get-ChildItem -Path (Split-Path (Get-Command ollama).Source) -Recurse -Filter "llama-server.exe" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName),
    [string]$Port = "11435"
)

function Get-ModelGGUFPath {
    param([string]$ModelName)
    $modelInfo = ollama show $ModelName --modelfile
    $fromLine = $modelInfo | Select-String "^FROM\s+(.+)" | ForEach-Object { $_.Matches.Groups[1].Value.Trim() }
    if ($fromLine -match "^sha256-") {
        $ollamaHome = if ($env:OLLAMA_MODELS) { $env:OLLAMA_MODELS } else { "$env:USERPROFILE\.ollama\models" }
        return Join-Path $ollamaHome "blobs" $fromLine
    }
    return $fromLine
}

function Start-RerankService {
    param([string]$ModelPath, [string]$Port)

    # 停止已有服务
    Get-Process -Name "llama-server" -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Seconds 2

    # 启动新服务
    Start-Process -FilePath $LlamaServer `
        -ArgumentList "--rerank", "--model", $ModelPath, "--port", $Port, "--host", "127.0.0.1", "--threads", "-1", "--api-prefix", "/v1", "--flash-attn", "auto", "--no-webui", "--ctx-size", "8192" `
        -WindowStyle Minimized

    # 等待服务就绪
    $maxWait = 30
    for ($i = 0; $i -lt $maxWait; $i++) {
        try {
            Invoke-RestMethod -Uri "http://127.0.0.1:$Port/health" -Method Get -TimeoutSec 1 | Out-Null
            return $true
        } catch { Start-Sleep -Seconds 1 }
    }
    return $false
}

function Test-RerankModel {
    param([string]$ModelName, [string]$Query, [array]$Documents)

    Write-Host "`n============================================================" -ForegroundColor Cyan
    Write-Host "模型: $ModelName" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan

    $modelPath = Get-ModelGGUFPath -ModelName $ModelName
    Write-Host "模型路径: $modelPath" -ForegroundColor Gray

    if (-not (Start-RerankService -ModelPath $modelPath -Port $Port)) {
        Write-Host "[失败] 服务启动超时" -ForegroundColor Red
        return $null
    }

    $body = @{
        model     = ""
        query     = $Query
        documents = $Documents
    } | ConvertTo-Json -Depth 3

    $start = Get-Date
    $response = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v1/rerank" -Method Post -Body $body -ContentType "application/json"
    $elapsed = ((Get-Date) - $start).TotalMilliseconds

    $sorted = $response.results | Sort-Object relevance_score -Descending

    Write-Host "推理耗时: ${elapsed}ms" -ForegroundColor Gray
    Write-Host "--- 排序结果 ---" -ForegroundColor Green

    $rank = 1
    foreach ($item in $sorted) {
        $idx = $item.index
        $score = $item.relevance_score
        $color = if ($rank -le 3) { "Green" } else { "White" }
        Write-Host ("[{0}] 分数: {1:N6} | {2}" -f $rank, $score, $Documents[$idx]) -ForegroundColor $color
        $rank++
    }

    return $sorted
}

# 测试数据
$query = "什么是机器学习？"
$documents = @(
    "机器学习是人工智能的一个分支，通过数据训练模型。",
    "深度学习是机器学习的一种，使用神经网络。",
    "Python 是一种编程语言，适合数据处理。",
    "今天天气很好，适合户外运动。",
    "神经网络可以模拟人脑的工作方式。",
    "机器学习算法包括监督学习、无监督学习和强化学习。"
)

# 对比模型
$models = @(
    "B-A-M-N/Qwen3-Reranker-0.6B-fp16:latest",
    "dengcao/bge-reranker-v2-m3:latest"
)

$results = @{}
foreach ($model in $models) {
    $results[$model] = Test-RerankModel -ModelName $model -Query $query -Documents $documents
}

# 对比分析
Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "对比总结" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 获取每个模型的 Top 3 索引
foreach ($model in $models) {
    $top3 = $results[$model] | Select-Object -First 3 | ForEach-Object { $_.index }
    Write-Host "$model Top3: $($top3 -join ', ')" -ForegroundColor White
}

# 停止服务
Get-Process -Name "llama-server" -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host "`n测试完成，服务已停止。" -ForegroundColor Cyan
```

---

## 九、常见问题

### Q1: 找不到 llama-server.exe

```powershell
# 如果 Ollama 安装目录不在 PATH，手动指定
$ollamaDir = "C:\Program Files\Ollama"  # 修改为你的安装路径
$llamaServer = Join-Path $ollamaDir "lib\ollama\llama-server.exe"
```

### Q2: 模型路径包含空格导致启动失败

```powershell
# 用引号包裹路径
$modelPath = '"' + (Get-ModelGGUFPath -ModelName "模型名") + '"'
```

### Q3: 服务启动后无法访问

- 检查防火墙是否放行端口
- 如果需远程访问，将 `--host` 设为 `0.0.0.0`
- 确认端口未被占用：`Get-NetTCPConnection -LocalPort 11435`

### Q4: 与 Ollama 主服务冲突

- Rerank 服务使用 **11435** 端口，与 Ollama 的 11434 不冲突
- 两者可同时运行

---

## 十、集成到 RAG 系统

### Python 示例

```python
import requests

def rerank_documents(query, documents, url="http://127.0.0.1:11435/v1/rerank"):
    response = requests.post(url, json={
        "model": "",
        "query": query,
        "documents": documents,
        "top_n": len(documents)
    })
    data = response.json()

    results = sorted(data["results"], key=lambda x: x["relevance_score"], reverse=True)
    return [
        {
            "document": documents[r["index"]],
            "score": r["relevance_score"],
            "index": r["index"]
        }
        for r in results
    ]

# 使用示例
docs = ["文档1...", "文档2...", "文档3..."]
ranked = rerank_documents("用户查询", docs)
for item in ranked[:3]:
    print(f"[{item['score']:.4f}] {item['document']}")
```

### LangChain 集成

```python
class OllamaReranker:
    def __init__(self, base_url="http://127.0.0.1:11435"):
        self.base_url = base_url

    def rerank(self, query, documents):
        import requests
        response = requests.post(
            f"{self.base_url}/v1/rerank",
            json={"model": "", "query": query, "documents": documents}
        )
        results = response.json()["results"]
        results.sort(key=lambda x: x["relevance_score"], reverse=True)
        return [(documents[r["index"]], r["relevance_score"]) for r in results]
```

---

## 十一、参考链接

- [Ollama 官方](https://ollama.com)
- [AuditAid/Qwen3_Reranker](https://ollama.com/AuditAid/Qwen3_Reranker)
- [audit-tool-skills/Rerank](https://github.com/AuditAIH/audit-tool-skills/tree/main/Rerank)
- [llama.cpp 文档](https://github.com/ggml-org/llama.cpp)
