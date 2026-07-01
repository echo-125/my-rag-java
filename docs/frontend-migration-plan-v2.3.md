# 个人 RAG 极客控制台 — 前端迁移与页面升级计划 V2.3（终稿）

> 基于 V2.2 修订，修补审查发现的全部问题。
>
> 修订日期：2026-07-01

---

## V2.2 → V2.3 变更清单

| # | 问题 | 严重度 | 处理 |
|---|------|--------|------|
| 1 | 聊天重试幂等性：声称"当前已满足"但后端实际无保护 | P1 | 重写 7.6 节聊天部分：阶段一需验证，若不幂等则前端必须创建新请求态 |
| 2 | Smoke test `/assets/` 目录检查会误报 | P2 | 删除 `/assets/` 检查，只校验提取出的实际资源文件 |
| 3 | 构建命令不一致：`VITE_BUILD_MODE=new` 与 `--mode production-new` 混用 | P2 | 统一为 `npm run build:new`，删除错误命令 |
| 4 | Vite mode 来源未解释 | P3 | 在 vite.config.ts 代码块上方加注释说明 |
| 5 | sanitizeSvg 缺少 `ALLOW_DATA_ATTR: false` | P3 | 补充 |
| 6 | new 模式是否保留 `/app/` 入口 | 开放 | 明确决策：保留作为兼容入口，短期可移除 |

---

## 一、聊天重试幂等性（替换 V2.2 7.6 节聊天部分）

### 后端源码核实结论

**后端 `ConversationService.addMessage()` 无幂等保护**：

```java
// ConversationService.java:139-154
private void addMessage(String sessionId, String role, Message message, String rawContent) {
    LinkedList<Message> queue = sessions.computeIfAbsent(sessionId, k -> new LinkedList<>());
    synchronized (queue) {
        queue.addLast(message);   // 直接追加，无去重
        // ...
    }
    // 写 DB（异步容错）
    // ...
}
```

**结论**：相同 query + sessionId 重复发送会产生：
- 2 条用户消息（内存 + DB）
- 2 次 LLM 调用
- 2 条 AI 回复（内存 + DB）

这不是幂等的。重试会导致会话中出现重复问答。

### 修正后的聊天断线策略（V2.3）

| 项目 | 决策 |
|------|------|
| 断线后是否自动重连 | **否** — 显示"连接中断"提示 + 手动重试按钮 |
| 重试时是否重放同一请求 | **否** — 后端无幂等保护，重放会产生重复消息 |
| 重试策略 | **创建新消息轮次** — 用户重新输入或确认后，前端将上次 query 作为新消息发送（追加到会话末尾，而非替换旧消息） |
| 已收到的内容 | **保留** — 断线前已渲染的内容保留在 UI 中，用户可看到部分内容 |
| 后端需确认 | **阶段一必须确认**：后端是否有计划增加幂等保护（如 request-id 去重）；若无，前端方案按上述策略锁定 |

**阶段一任务**：

```
□ 核实后端 ConversationService.addMessage() 是否有幂等保护
□ 确认结果：无保护（已确认）
□ 锁定前端策略：retry() 不重放，而是追加新消息
□ 如果未来后端增加幂等保护，前端 retry() 可升级为安全重放
```

**composable 接口（V2.3 修正）**：

```typescript
// composables/useStreamChat.ts
export function useStreamChat() {
  const status = ref<'idle' | 'streaming' | 'completed' | 'error' | 'interrupted'>('idle')
  const errorMessage = ref('')

  async function send(query: string, modelKey: string, sessionId: string) {
    status.value = 'streaming'
    errorMessage.value = ''
    try {
      // ... SSE 流式逻辑
    } catch (e) {
      if (status.value === 'streaming') {
        status.value = 'interrupted'
        errorMessage.value = '连接中断，已收到的内容已保留'
      } else {
        status.value = 'error'
        errorMessage.value = e.message
      }
    }
  }

  /**
   * 重试：不重放上次请求，而是追加新消息。
   * 用户在 UI 中确认后调用此方法，query 可以是用户重新输入的，
   * 也可以是上次的 query（但会作为新消息追加到会话末尾）。
   */
  function retry(query: string, modelKey: string, sessionId: string) {
    send(query, modelKey, sessionId)
  }

  return { status, errorMessage, send, retry }
}
```

**UI 行为**：

```
用户发送消息 → 流式渲染中 → 连接中断
  ↓
UI 显示："连接中断，已收到的内容已保留" + [重试] 按钮
  ↓
用户点击 [重试]：
  - 方案 A（推荐）：消息输入框中预填上次 query，用户可编辑后重新发送
  - 方案 B：直接用上次 query 追加新消息
  ↓
新消息追加到会话末尾（旧的中断消息保留在上方）
```

### 入库断线（保留 V2.2 原文）

入库部分的断线策略不需要修改——入库本身就是"从零开始"的幂等操作（后端会先清空旧 chunks 再重建）。

---

## 二、Smoke Test 修正（替换 V2.2 8.5 节脚本部分）

### 8.5 脚本（V2.3 修正）

```powershell
# scripts/smoke-test.ps1
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("legacy", "both", "new")]
    [string]$Mode,

    [string]$BaseUrl = "http://localhost:8080"
)

$errors = @()

function Test-Url {
    param([string]$Url, [int]$ExpectedStatus, [string]$Description)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
        $actualStatus = $response.StatusCode
    } catch {
        $actualStatus = $_.Exception.Response.StatusCode.value__
    }
    if ($actualStatus -eq $ExpectedStatus) {
        Write-Host "[PASS] $Description -> $actualStatus" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] $Description -> $actualStatus (期望 $ExpectedStatus)" -ForegroundColor Red
        $errors += $Description
    }
}

Write-Host "=== Smoke Test (mode=$Mode) ===" -ForegroundColor Cyan

switch ($Mode) {
    "legacy" {
        Test-Url "$BaseUrl/" 200 "GET / -> 旧前端"
    }
    "both" {
        Test-Url "$BaseUrl/" 200 "GET / -> 旧前端"
        Test-Url "$BaseUrl/app/" 200 "GET /app/ -> 新前端入口"
        Test-Url "$BaseUrl/api/dashboard/stats" 200 "API 可达"
    }
    "new" {
        Test-Url "$BaseUrl/" 200 "GET / -> 新前端入口"
        Test-Url "$BaseUrl/api/dashboard/stats" 200 "API 可达"
        # 从 index.html 提取实际 JS 资源并校验（可靠检查）
        try {
            $html = (Invoke-WebRequest -Uri "$BaseUrl/" -UseBasicParsing).Content
            $jsMatch = [regex]::Match($html, 'src="([^"]*\.js)"')
            if ($jsMatch.Success) {
                $jsPath = $jsMatch.Groups[1].Value
                if ($jsPath.StartsWith("./")) {
                    $jsUrl = "$BaseUrl/$($jsPath.Substring(2))"
                } else {
                    $jsUrl = "$BaseUrl$jsPath"
                }
                Test-Url $jsUrl 200 "JS 资源 $jsPath 可访问"
            } else {
                Write-Host "[WARN] 未找到 JS 资源引用" -ForegroundColor Yellow
            }
        } catch {
            Write-Host "[ERROR] 无法获取 index.html: $_" -ForegroundColor Red
            $errors += "index.html 获取失败"
        }
    }
}

Write-Host ""
if ($errors.Count -eq 0) {
    Write-Host "=== All smoke tests passed ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== $($errors.Count) test(s) failed ===" -ForegroundColor Red
    exit 1
}
```

**变更**：
- 删除了 `GET /assets/` 检查（目录不一定返回 200）
- 只校验从 index.html 提取的实际 JS 资源文件

### 执行时机（保留 V2.2 原文表格）

---

## 三、构建命令统一（替换 V2.2 7.2 节构建部分）

### Vite 配置（V2.3 加注释）

```typescript
// vite.config.ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// mode 来自 Vite CLI --mode 参数（见下方 package.json 脚本）
//   npm run build        → mode = 'production'（默认）→ base: './'
//   npm run build:new    → mode = 'production-new'    → base: '/'
export default defineConfig(({ mode }) => ({
  base: mode === 'production-new' ? '/' : './',
  build: {
    outDir: 'dist',
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
}))
```

### package.json（V2.3 统一命令）

```json
{
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "build:new": "vite build --mode production-new",
    "preview": "vite preview"
  }
}
```

**删除**了 V2.2 中 `VITE_BUILD_MODE=new npm run build` 的写法。只保留 `npm run build:new`。

---

## 四、sanitizeSvg 补充 ALLOW_DATA_ATTR（在 V2.1 5.3 节修正）

```typescript
function sanitizeSvg(svg: string): string {
  const SVG_CONFIG = {
    ALLOWED_TAGS: [
      'svg', 'g', 'path', 'circle', 'rect', 'line', 'polyline', 'polygon',
      'text', 'tspan', 'marker', 'defs', 'clipPath', 'use',
      'style', 'title', 'desc',
    ],
    ALLOWED_ATTR: [
      'viewBox', 'xmlns', 'width', 'height', 'class', 'id',
      'fill', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin',
      'd', 'transform', 'x', 'y', 'x1', 'y1', 'x2', 'y2',
      'rx', 'ry', 'cx', 'cy', 'r',
      'marker-end', 'marker-start', 'refX', 'refY', 'markerWidth', 'markerHeight',
      'points', 'opacity', 'font-size', 'font-family', 'text-anchor',
      'dominant-baseline', 'alignment-baseline',
    ],
    ALLOW_DATA_ATTR: false,  // ← V2.3 补充：与 sanitizeHtml 保持一致
  }
  return DOMPurify.sanitize(svg, SVG_CONFIG) as string
}
```

---

## 五、new 模式 /app/ 入口决策

**决策**：new 模式下保留 `/app/` 入口，作为兼容路径短期存在。

| 模式 | `/` | `/app/` | 说明 |
|------|-----|---------|------|
| `legacy` | 旧前端 | 404 | — |
| `both` | 旧前端 | 新前端 | 开发期并存 |
| `new` | 新前端 | 新前端 | `/app/` 仍可访问（兼容 bookmark），未来可移除 |

理由：用户可能在 `both` 模式期间收藏了 `/app/` 链接，切到 `new` 后不应立即失效。

---

## 六、保留的 V2/V2.2 内容

以下章节不做修改：

- 一、阶段划分（8 阶段 + 里程碑）
- 二、Spike 技术预研（4.1-4.2/4.4）
- 三、安全策略（5.1/5.2/5.4/5.5）
- 四、API 契约（6.1-6.4）
- 五、测试策略（8.1-8.4）
- 六、页面迁移策略（九）
- 七、目录结构（十）
- 八、第一步（十一）
- 九、V1→V2 对照表（十二）

---

## 七、修订后是否建议批准实施

**建议批准。**

V2.3 修补了 V2.2 审查发现的全部问题：

| 问题 | 修补状态 |
|------|----------|
| 聊天重试幂等性假设 | 源码核实确认无保护，策略改为追加新消息而非重放 |
| Smoke test /assets/ 误报 | 删除目录检查，只校验提取出的实际资源 |
| 构建命令不一致 | 统一为 `npm run build:new`，删除错误命令 |
| Vite mode 来源不明 | 加注释说明 `--mode` 参数 |
| sanitizeSvg 配置遗漏 | 补充 `ALLOW_DATA_ATTR: false` |
| /app/ 入口去留 | 明确保留作为兼容路径 |

**可以据此计划进入阶段一实施。**
