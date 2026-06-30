# Qwen3-Reranker 安装服务手册（Agent 专用）

> **目标读者**：AI Agent（Claude/Cursor/Copilot 等）
> **任务**：在 Windows 机器上完成 Qwen3-Reranker 模型安装，使 `start-qwen3-reranker.bat` 可直接启动服务。
> **执行方式**：逐条运行以下检查与命令，确认每一步通过后再继续。

---

## 一、环境要求

### 1.1 环境清单

| 项目 | 要求 | 检查命令 |
|------|------|----------|
| 操作系统 | Windows 10/11 / Windows Server 2019+ | `winver` |
| Ollama | 已安装并运行（端口 11434） | `Get-Command ollama` |
| PowerShell | 5.1 或更高版本 | `$PSVersionTable.PSVersion` |
| 网络 | 可访问 Ollama 模型库（或已本地缓存模型） | `Test-NetConnection ollama.com -Port 443` |

### 1.2 前置条件检查

```powershell
# 1.2.1 确认 ollama 命令存在
Get-Command ollama -ErrorAction SilentlyContinue
# ❌ 未找到 → 前往 ollama.com 下载安装，安装后重启终端
# ✅ 找到 → 继续

# 1.2.2 确认 Ollama 服务在运行
Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/tags" -Method Get -TimeoutSec 5
# ❌ 连接失败 → 启动 Ollama（开始菜单 → Ollama）
# ✅ 返回模型列表 → 继续

# 1.2.3 确认 PowerShell 版本
$PSVersionTable.PSVersion
# ❌ 版本过低 → 升级 PowerShell
# ✅ 继续
```

---

## 二、安装重排模型

### 2.1 拉取模型（三选一）

```powershell
# 方案 A（推荐）：Qwen3-Reranker-0.6B fp16，排序质量最高
ollama pull B-A-M-N/Qwen3-Reranker-0.6B-fp16:latest

# 方案 B：bge-reranker-v2-m3，质量相当，体积略小
ollama pull dengcao/bge-reranker-v2-m3:latest

# 方案 C：AuditAid Q8 量化版，体积最小（607MB）
ollama pull AuditAid/Qwen3_Reranker:0.6B_Q8
```

| 方案 | 模型名 | 大小 | 说明 |
|------|--------|------|------|
| A（推荐） | `B-A-M-N/Qwen3-Reranker-0.6B-fp16:latest` | ~1.2 GB | 排序质量最高 |
| B | `dengcao/bge-reranker-v2-m3:latest` | ~1.2 GB | 多语言支持好 |
| C | `AuditAid/Qwen3_Reranker:0.6B_Q8` | ~607 MB | 体积最小，适合低配机器 |

### 2.2 验证模型已拉取

```powershell
ollama list
# 输出中应包含对应的模型条目
```

### 2.3 获取模型 GGUF 文件路径

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
Write-Host "GGUF 路径: $modelPath"
Write-Host "文件存在: $(Test-Path -LiteralPath $modelPath)"
```

- ✅ 文件存在 → 安装完成，可进入启动步骤
- ❌ 文件不存在 → 重新执行 `ollama pull` 或检查 Ollama 模型存储目录

### 2.4 模型路径含空格处理

```powershell
# 若路径含空格，启动时需引号包裹
$quotedPath = '"' + $modelPath + '"'
```

---

## 三、定位关键文件

### 3.1 找到 llama-server.exe

Ollama 安装时自带 `llama-server.exe`，无需单独编译：

```powershell
# 自动查找
$llamaServer = Get-ChildItem -Path (Split-Path (Get-Command ollama).Source) -Recurse -Filter "llama-server.exe" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
Write-Host "llama-server.exe: $llamaServer"
Write-Host "文件存在: $(Test-Path -LiteralPath $llamaServer)"
```

**常见位置：**
- 默认安装：`C:\Program Files\Ollama\lib\ollama\llama-server.exe`
- 自定义安装：`E:\ollama\lib\ollama\llama-server.exe`

### 3.2 确认端口 11435 空闲

```powershell
try {
    $tcp = Get-NetTCPConnection -LocalPort 11435 -ErrorAction SilentlyContinue
    if ($tcp) {
        Write-Host "警告: 端口 11435 已被 PID $($tcp.OwningProcess) 占用"
    } else {
        Write-Host "端口 11435 空闲"
    }
} catch {
    Write-Host "端口 11435 空闲"
}
```

---

## 四、故障排除

### 4.1 找不到 llama-server.exe

```powershell
# 手动指定 Ollama 安装目录
$ollamaDir = "E:\ollama"  # 改为实际安装路径
$llamaServer = "$ollamaDir\lib\ollama\llama-server.exe"
```

如果仍找不到，说明 Ollama 安装不完整，建议重新安装。

### 4.2 拉取模型失败

```powershell
# 检查网络连通性
Test-NetConnection -ComputerName ollama.com -Port 443

# 网络受限时，尝试更小的量化版本：
ollama pull AuditAid/Qwen3_Reranker:0.6B_Q8
```

### 4.3 磁盘空间不足

```powershell
# 查看 Ollama 模型占用
$ollamaHome = if ($env:OLLAMA_MODELS) { $env:OLLAMA_MODELS } else { "$env:USERPROFILE\.ollama\models" }
Get-ChildItem -Path $ollamaHome\blobs -Recurse -Filter "sha256-*" | Measure-Object -Property Length -Sum | Select-Object Count, Sum
```

---

## 五、安装完成确认清单

| 检查项 | 命令 | 预期结果 |
|--------|------|----------|
| Ollama 运行 | `Invoke-RestMethod http://127.0.0.1:11434/api/tags` | 返回 JSON 列表 |
| 模型已拉取 | `ollama list` | 包含 Qwen3-Reranker |
| GGUF 文件存在 | `Test-Path $modelPath` | `True` |
| llama-server 存在 | `Test-Path $llamaServer` | `True` |
| 端口 11435 空闲 | `Get-NetTCPConnection -LocalPort 11435` | 无结果 |

**全部通过后**，可执行 `start-qwen3-reranker.bat` 启动服务。启动后的验证步骤参见 `RERANK_START.md`。
