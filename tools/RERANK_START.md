# Qwen3-Reranker 启动服务手册（Agent 专用）

> **目标读者**：AI Agent（Claude/Cursor/Copilot 等）
> **任务**：启动 / 停止 / 验证 / 调用 Qwen3-Reranker 重排服务。
> **前置条件**：已按 `RERANK_INSTALL.md` 完成安装。

---

## 一、快速启动（推荐）

```
# 启动
start-qwen3-reranker.bat

# 停止
stop-qwen3-reranker.bat
```

脚本会自动检测 Ollama 安装位置和模型文件路径，静默启动 `llama-server`。

---

## 二、手动启动（Agent 逐条执行）

### 2.1 停止旧服务（避免端口冲突）

```powershell
Get-Process -Name "llama-server" -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
```

### 2.2 定位 llama-server.exe

```powershell
$llamaServer = Get-ChildItem -Path (Split-Path (Get-Command ollama).Source) -Recurse -Filter "llama-server.exe" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
Write-Host "llama-server: $llamaServer"
```

### 2.3 定位模型 GGUF 文件

```powershell
$modelName = "B-A-M-N/Qwen3-Reranker-0.6B-fp16:latest"
$modelInfo = ollama show $modelName --modelfile
$fromLine = $modelInfo | Select-String "^FROM\s+(.+)" | ForEach-Object { $_.Matches.Groups[1].Value.Trim() }

if ($fromLine -match "^sha256-") {
    $ollamaHome = if ($env:OLLAMA_MODELS) { $env:OLLAMA_MODELS } else { "$env:USERPROFILE\.ollama\models" }
    $modelPath = Join-Path $ollamaHome "blobs" $fromLine
} else {
    $modelPath = $fromLine
}
Write-Host "模型: $modelPath"
```

### 2.4 启动 llama-server 重排服务

```powershell
$port = 11435
$hostAddr = "127.0.0.1"

Start-Process -FilePath $llamaServer `
    -ArgumentList "--rerank", "--model", "`"$modelPath`"", "--port", $port, "--host", $hostAddr, "--threads", "-1", "--api-prefix", "/v1", "--flash-attn", "auto", "--no-webui" `
    -WindowStyle Hidden

Write-Host "服务已启动: http://${hostAddr}:${port}/v1/rerank"
```

### 2.5 等待服务就绪

```powershell
$maxWait = 60
$ready = $false
for ($i = 0; $i -lt $maxWait; $i++) {
    try {
        $body = '{"query":"test","documents":["test"],"top_n":1}'
        $r = Invoke-RestMethod -Uri "http://127.0.0.1:$port/v1/rerank" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 5 -ErrorAction Stop
        if ($r.results) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
if ($ready) { Write-Host "服务就绪" } else { Write-Host "服务可能未完全就绪" }
```

---

## 三、服务验证

### 3.1 验证进程运行

```powershell
Get-Process -Name "llama-server" -ErrorAction SilentlyContinue | Format-Table Id, StartTime
```

### 3.2 验证 Rerank API

```powershell
$query = "什么是机器学习？"
$documents = @(
    "机器学习是人工智能的一个分支，通过数据训练模型。",
    "深度学习是机器学习的一种，使用神经网络。",
    "Python 是一种编程语言，适合数据处理。",
    "今天天气很好，适合户外运动。"
)

$body = @{
    query     = $query
    documents = $documents
    top_n     = 4
} | ConvertTo-Json -Depth 3

$response = Invoke-RestMethod -Uri "http://127.0.0.1:11435/v1/rerank" -Method Post -Body $body -ContentType "application/json"

Write-Host "Token: $($response.usage.total_tokens)"
$response.results | Sort-Object relevance_score -Descending | ForEach-Object {
    Write-Host "[$($_.index)] 分数: $($_.relevance_score.ToString('F4')) | $($documents[$_.index])"
}
```

**成功标志**：返回 `results` 数组，每个文档有 `index` 和 `relevance_score` 字段。

### 3.3 验证 /health 端点（如支持）

```powershell
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:11435/health" -Method Get -TimeoutSec 3
    Write-Host "Health: $($health | ConvertTo-Json -Compress)"
} catch {
    Write-Host "Health 端点不可用（某些版本不支持，不影响使用）"
}
```

---

## 四、API 调用详解

### 4.1 请求格式

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
```

### 4.2 响应格式

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

### 4.3 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `index` | int | 文档在原始列表中的索引 |
| `relevance_score` | float | 相关性分数（0~1，越高越相关） |
| `prompt_tokens` | int | 输入 token 数 |
| `total_tokens` | int | 总 token 数 |

### 4.4 使用示例

```powershell
# 重排结果按分数降序排列
$response.results | Sort-Object relevance_score -Descending | ForEach-Object {
    $idx = $_.index
    $score = $_.relevance_score
    Write-Host ("[{0}] 分数: {1:N4} | {2}" -f $idx, $score, $documents[$idx])
}
```

---

## 五、完整测试脚本

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

## 六、停止服务

### 6.1 停止方式 A：运行 stop 脚本

```
stop-qwen3-reranker.bat
```

### 6.2 停止方式 B：手动停止

```powershell
# 停止所有 llama-server 进程
Get-Process -Name "llama-server" -ErrorAction SilentlyContinue | Stop-Process -Force

# 或按端口停止
$conn = Get-NetTCPConnection -LocalPort 11435 -ErrorAction SilentlyContinue
if ($conn) { Stop-Process -Id $conn.OwningProcess -Force }

Start-Sleep -Seconds 1
Write-Host "服务已停止，端口 11435 已释放"
```

### 6.3 验证已停止

```powershell
try {
    Invoke-RestMethod -Uri "http://127.0.0.1:11435/v1/rerank" -Method Post -Body '{"query":"test","documents":["test"]}' -ContentType "application/json" -TimeoutSec 3
    Write-Host "警告: 服务仍在运行"
} catch {
    Write-Host "服务已停止"
}
```

---

## 七、启动参数参考

| 参数 | 值 | 说明 |
|------|-----|------|
| `--rerank` | （无值） | **必需**，启用重排端点 |
| `--model` | GGUF 文件路径 | **必需**，模型文件 |
| `--port` | `11435` | 监听端口（避免与 Ollama 11434 冲突） |
| `--host` | `127.0.0.1` | 监听地址；`0.0.0.0` 允许远程访问 |
| `--api-prefix` | `/v1` | API 前缀，兼容 OpenAI 格式 |
| `--flash-attn` | `auto` | Flash Attention 加速（NVIDIA GPU 推荐） |
| `--threads` | `-1` | 线程数（-1=自动，根据 CPU 核心数） |
| `--ctx-size` | `8192` | 上下文窗口大小 |
| `--no-webui` | （无值） | 仅 API 模式，不启动 Web 界面 |
| `--mlock` | （无值） | 锁定模型到内存（可选，提升响应速度） |

---

## 八、常见问题（Agent 诊断流程）

### 问题：启动后 API 返回 502 / 连接拒绝

```
诊断步骤：
1. Get-Process -Name "llama-server"          → 检查进程是否存活
2. Get-NetTCPConnection -LocalPort 11435      → 检查端口是否监听
3. 检查命令行参数中的模型路径是否正确
4. 检查是否有防病毒软件拦截
```

### 问题：模型加载慢 / 响应时间长

```
原因：首次加载需要将模型读入内存
解决：
- 添加 --mlock 参数锁定模型
- 减少 --ctx-size（如设为 4096）
- 使用更小的量化版（AuditAid/Qwen3_Reranker:0.6B_Q8）
```

### 问题：与 Ollama 主服务冲突

- Rerank 服务使用 **11435** 端口，与 Ollama 的 **11434** 不冲突
- 两者可同时运行

### 问题：模型路径包含空格导致启动失败

```powershell
# 用引号包裹路径
$quotedPath = '"' + $modelPath + '"'
```

---

## 九、集成到 My RAG Java 项目

### 9.1 应用配置

```yaml
# application.yml
reranking:
  provider: ollama
  base_url: http://127.0.0.1:11435
  model_name: ""
```

### 9.2 通过 API 添加配置

```bash
curl -X POST http://localhost:8080/api/reranking-configs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "本地 Qwen3-Reranker",
    "provider": "ollama",
    "modelName": "",
    "baseUrl": "http://127.0.0.1:11435",
    "isActive": true
  }'
```

### 9.3 Python 调用示例

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

docs = ["文档1...", "文档2...", "文档3..."]
ranked = rerank_documents("用户查询", docs)
for item in ranked[:3]:
    print(f"[{item[\"score\"]:.4f}] {item[\"document\"]}")
```

### 9.4 Java RerankingService 调用

```java
// 在 RAG 系统内部，RerankingService 已封装好调用逻辑
// 配置激活后，检索结果自动经过 rerank 精排
List<Content> reranked = rerankingService.rerank(query, candidates);
```

---

## 十、参考链接

- [Ollama 官方](https://ollama.com)
- [AuditAid/Qwen3_Reranker](https://ollama.com/AuditAid/Qwen3_Reranker)
- [audit-tool-skills/Rerank](https://github.com/AuditAIH/audit-tool-skills/tree/main/Rerank)
- [llama.cpp 文档](https://github.com/ggml-org/llama.cpp)
