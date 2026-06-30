# RAG 系统全功能测试用例设计

> 目标：覆盖 6 大功能模块（对话历史、混合检索、Reranking、查询改写、评估体系、Agent 工具调用），
> 每个模块包含 API 层测试（JUnit + MockMvc）和前端层测试（Playwright）。
>
> 先决条件：服务启动在 `http://localhost:8080`，数据库已初始化，至少有一个激活的 LLM 模型。

---

## 目录

1. [P0 — 对话历史持久化](#p0--对话历史持久化)
2. [P1 — 混合检索（BM25 + 向量）](#p1--混合检索bm25--向量)
3. [P2 — Reranking 精排](#p2--reranking-精排)
4. [P3 — 查询改写](#p3--查询改写)
5. [P4 — 评估体系](#p4--评估体系)
6. [P5 — Agent 工具调用](#p5--agent-工具调用)
7. [P5+ — 在线反馈](#p5--在线反馈)
8. [测试数据准备](#测试数据准备)
9. [测试执行顺序](#测试执行顺序)

---

## P0 — 对话历史持久化

### 后端 API 测试（JUnit + MockMvc）

#### TC-P0-001: 创建会话
- **端点**: `POST /api/sessions`（隐性：通过 chat 时自动创建）
- **步骤**: 调用聊天接口传入新 sessionId
- **预期**: 返回流，DB `chat_session` 新增记录，`title` 截取前 30 字符
- **验证**: `SELECT * FROM chat_session WHERE id = ?` 存在

#### TC-P0-002: 自动创建会话
- **步骤**: 调用 `POST /api/chat/stream` 不传 sessionId
- **预期**: 后端自动生成 UUID，会话创建成功
- **验证**: 响应正常，无报错

#### TC-P0-003: 用户消息持久化
- **步骤**: 发送消息后查询 `chat_message` 表
- **预期**: role='user', content 与输入一致
- **验证**: `SELECT COUNT(*) FROM chat_message WHERE session_id = ? AND role = 'user'`

#### TC-P0-004: 助手消息持久化
- **步骤**: 等待流结束后查询 `chat_message`
- **预期**: role='assistant', content 包含完整回答
- **验证**: COUNT = 1, content LIKE '%...'

#### TC-P0-005: 滑动窗口裁剪
- **步骤**: 连续发送 > maxRounds*2 条消息（默认 maxRounds=10 → 21 条）
- **预期**: 内存中保留最近 20 条，最早的消息被移除
- **验证**: `getHistory(sessionId)` 返回的列表 size == 20

#### TC-P0-006: DB 重启加载
- **步骤**: 发送消息 → 重启应用 → 调用 `getHistory(sessionId)`
- **预期**: 从 DB 加载历史消息到内存
- **验证**: 返回的消息列表非空且内容正确

#### TC-P0-007: 删除会话级联删除消息
- **步骤**: `DELETE /api/sessions/{id}`
- **预期**: `chat_session` 和关联的 `chat_message` 都被删除
- **验证**: SELECT 两者均为空

#### TC-P0-008: 列表按更新时间倒序
- **步骤**: 创建多个会话，依次发送消息
- **预期**: `GET /api/sessions` 返回按 updated_at DESC
- **验证**: 数组中最后一个会话是最新活跃的

#### TC-P0-009: 会话不存在时返回空列表
- **步骤**: `GET /api/sessions/{random-uuid}/messages`
- **预期**: 返回空数组 `[]`，不抛异常

#### TC-P0-010: 会话 ID 格式非法处理
- **步骤**: `GET /api/sessions/invalid-id/messages`
- **预期**: 内部 catch 异常，返回空数组
- **验证**: 无 500 错误

---

### 前端测试（Playwright）

#### TC-P0-F01: 会话列表显示
- **步骤**: 打开首页，等待侧边栏加载
- **预期**: 左侧显示会话列表，包含标题和时间
- **验证**: `.session-list` 中有 `.session-item` 元素

#### TC-P0-F02: 新建会话
- **步骤**: 点击"新建对话"按钮
- **预期**: 清空聊天区域，显示欢迎页，生成新 sessionId
- **验证**: 聊天区出现欢迎消息

#### TC-P0-F03: 切换会话
- **步骤**: 创建两个会话，在侧边栏点击另一个
- **预期**: 聊天区域切换为对应会话的消息
- **验证**: 消息内容与之前一致

#### TC-P0-F04: 发送消息后历史记录保留
- **步骤**: 发送消息 → 刷新页面 → 查看侧边栏
- **预期**: 会话仍在列表中，点击可恢复历史
- **验证**: 消息从后端加载正确

#### TC-P0-F05: 多轮对话上下文
- **步骤**: 连续发送 3 条关联消息
- **预期**: 后端 history 包含之前消息，回答有上下文
- **验证**: 第 3 条回答引用前两条内容

#### TC-P0-F06: 删除会话
- **步骤**: 点击会话旁的删除按钮
- **预期**: 会话从列表移除，聊天区清空
- **验证**: `GET /api/sessions` 不包含该 ID

#### TC-P0-F07: 并发消息安全
- **步骤**: 快速连续发送 5 条消息
- **预期**: 所有消息都保存，无丢失
- **验证**: DB 中有 5 条 user + 5 条 assistant 消息

---

## P1 — 混合检索（BM25 + 向量）

### 后端 API 测试

#### TC-P1-001: 向量检索基础
- **步骤**: 确保知识库有数据，调用 `retrieveAndRerank(query, [])`
- **预期**: 返回 Content 列表，每个含 file_path, type, signature, text
- **验证**: list 非空，每条 content.metadata 有 file_path

#### TC-P1-002: BM25 关键词检索
- **步骤**: 设置 `enable_bm25=true`，检索含特定关键词的查询
- **预期**: BM25 检索器通过 `tsvector/tsquery` 匹配
- **验证**: 结果中有包含该关键词的文档

#### TC-P1-003: 关闭 BM25 仅用向量
- **步骤**: 设置 `enable_bm25=false`，执行检索
- **预期**: 只用 `EmbeddingStoreContentRetriever`
- **验证**: `buildRetrieverWithPool()` 返回 EmbeddingStoreContentRetriever

#### TC-P1-004: 混合检索结果去重
- **步骤**: 同一文档被向量和 BM25 同时召回
- **预期**: 去重后同一 file_path|type|signature 只保留一条
- **验证**: 结果中无重复 key

#### TC-P1-005: minScore 过滤
- **步骤**: 设置 `min_score=0.9`（高阈值）
- **预期**: 低分结果被过滤
- **验证**: 结果数减少或为空

#### TC-P1-006: maxResults 限制
- **步骤**: 设置 `max_results=3`
- **预期**: 最多返回 3 条
- **验证**: `contents.size() <= 3`

#### TC-P1-007: 检索结果包含元数据
- **步骤**: 检索后检查 Content 的 metadata
- **预期**: file_path, type, signature 均非空
- **验证**: 每个 Content.textSegment().metadata() 包含必需字段

#### TC-P1-008: 检索耗时记录
- **步骤**: 调用检索后查看 log
- **预期**: log 包含 `检索管线总耗时: XXms`
- **验证**: 日志输出

---

### 前端测试（Playwright）

#### TC-P1-F01: 引用来源展示
- **步骤**: 提问知识库中的问题，等待流式响应
- **预期**: 引用区域显示 [1][2][3] 等引用标签
- **验证**: `.citation-pill` 元素存在

#### TC-P1-F02: 引用点击展示文件信息
- **步骤**: 点击引用标签
- **预期**: tooltip 显示完整文件路径
- **验证**: `title` 属性包含文件路径

#### TC-P1-F03: 无结果时引用区隐藏
- **步骤**: 提问知识库外的问题
- **预期**: 引用区域不显示
- **验证**: citations 容器为空或隐藏

#### TC-P1-F04: BM25 开关切换
- **步骤**: 在设置页面切换 BM25 开关
- **预期**: 下次检索行为改变
- **验证**: 发送相同问题，结果不同

---

## P2 — Reranking 精排

### 后端 API 测试

#### TC-P2-001: Reranking 降级（无激活模型）
- **步骤**: 确保无激活的 reranking 配置，调用 `rerank()`
- **预期**: 返回原始顺序，log 有降级警告
- **验证**: 结果顺序不变，无异常

#### TC-P2-002: Reranking 精排排序
- **步骤**: 配置激活的 Ollama rerank 模型，调用 `rerank()`
- **预期**: 结果按分数降序排列
- **验证**: `scores[i] >= scores[i+1]`

#### TC-P2-003: Reranking 候选池大小
- **步骤**: 设置 `reranking_pool_size=20`，检索后 rerank
- **预期**: 传入 rerank 的候选数 = pool_size
- **验证**: log 中候选数量

#### TC-P2-004: Reranking Top-N 截断
- **步骤**: 设置 `reranking_top_n=3`
- **预期**: 最终返回最多 3 条
- **验证**: `contents.size() <= 3`

#### TC-P2-005: Reranking API 超时降级
- **步骤**: 配置无效的 Ollama URL，触发 rerank
- **预期**: HTTP 超时，返回原始顺序
- **验证**: 无异常传播，log 有降级警告

#### TC-P2-006: Reranking API 400/500 降级
- **步骤**: 配置返回错误的 rerank 服务
- **预期**: 捕获异常，返回原始顺序
- **验证**: 响应正常，无 500

#### TC-P2-007: Reranking 单条不处理
- **步骤**: candidates 大小为 1
- **预期**: 直接返回原列表
- **验证**: `rerank()` 返回原列表引用

#### TC-P2-008: Reranking 文档截断
- **步骤**: 传入超长文档（>2000 字符）
- **预期**: 截取前 2000 字符 + "..." 发送
- **验证**: 发送的 documents 中该条长度 <= 2003

#### TC-P2-009: Reranking 配置 CRUD
- **步骤**: 创建 → 查询 → 更新 → 激活 → 删除配置
- **预期**: 每步返回正确状态
- **验证**: REST API 各端点

#### TC-P2-010: Reranking 测试连接
- **步骤**: `POST /api/reranking-configs/{id}/test`
- **预期**: 返回 TestResult（success, latency, message）
- **验证**: 状态码 200

---

### 前端测试（Playwright）

#### TC-P2-F01: 启用 Reranking 配置
- **步骤**: 在 Reranking 页面创建 Ollama 配置并激活
- **预期**: 配置列表显示 is_active=true
- **验证**: 表格行有激活标识

#### TC-P2-F02: 测试连接
- **步骤**: 点击"测试连接"按钮
- **预期**: 显示 success 或 error 结果 toast
- **验证**: toast 出现

#### TC-P2-F03: 切换 Reranking 启用
- **步骤**: 在设置页切换 `enable_reranking` 开关
- **预期**: 后续对话使用 reranking
- **验证**: 响应时间略增（rerank 调用有耗时）

---

## P3 — 查询改写

### 后端 API 测试

#### TC-P3-001: 查询改写正常执行
- **步骤**: 设置 `enable_query_rewrite=true`，发送含指代的查询
- **预期**: 改写后的 query 将指代替换为具体名称
- **验证**: log 包含 `查询改写: 'xxx' → 'yyy'`

#### TC-P3-002: 改写降级（LLM 失败）
- **步骤**: 模拟 LLM 调用异常
- **预期**: 返回 null，使用原始 query
- **验证**: 检索正常执行，无异常

#### TC-P3-003: 改写空 query 处理
- **步骤**: `rewrite(null, history)` 和 `rewrite("", history)`
- **预期**: 返回原始值
- **验证**: 无 NPE

#### TC-P3-004: 改写结果清理
- **步骤**: LLM 返回带引号或前缀的文本
- **预期**: 去除引号和前缀
- **验证**: `replaceAll("^['\"]|['\"]$", "")` 生效

#### TC-P3-005: 无历史时的改写
- **步骤**: `rewrite("hello", [])`
- **预期**: 仍然执行改写，不因空历史失败
- **验证**: 返回非 null 结果或 null（降级）

#### TC-P3-006: 对话历史注入
- **步骤**: 连续对话后发送指代性问题（如"刚才说的那个"）
- **预期**: 改写将"那个"替换为具体实体名
- **验证**: 检索结果相关

---

### 前端测试（Playwright）

#### TC-P3-F01: 启用查询改写
- **步骤**: 在设置页切换 `enable_query_rewrite` 开关
- **预期**: 后端下次检索使用改写后的 query
- **验证**: 日志可确认改写执行

#### TC-P3-F02: 指代消解
- **步骤**: 发送"这个项目的入口是什么" → 发送"它支持哪些数据库"
- **预期**: 第二句"它"被改写为项目名
- **验证**: 检索结果相关

---

## P4 — 评估体系

### 后端 API 测试

#### TC-P4-001: 种子测试集自动导入
- **步骤**: 启动应用，检查 `evaluation_testset` 和 `evaluation_testcase`
- **预期**: 默认测试集"默认测试集"已存在，包含 testset.json 的用例
- **验证**: `SELECT COUNT(*) FROM evaluation_testcase`

#### TC-P4-002: 幂等导入
- **步骤**: 已有数据时再次启动
- **预期**: 不重复导入
- **验证**: 用例数不变

#### TC-P4-003: 启动评估任务
- **步骤**: `POST /api/evaluation/run { testsetId, k: 5 }`
- **预期**: 返回 202 Accepted，包含 batchId
- **验证**: `evaluation_batch` 新增记录，status='running'

#### TC-P4-004: 评估任务执行
- **步骤**: 启动任务后等待完成（VirtualThread 异步）
- **预期**: status='completed'，指标字段非 null
- **验证**: SELECT status, precision_at_k, recall, mrr, hit_rate FROM batch

#### TC-P4-005: 评估取消
- **步骤**: 启动任务 → 立即 `POST /api/evaluation/run/{id}/cancel`
- **预期**: cancelled=true，任务停止
- **验证**: status='cancelled'

#### TC-P4-006: 评估进度查询
- **步骤**: 启动任务后轮询 `GET /api/evaluation/run/{id}/status`
- **预期**: completedCases 递增
- **验证**: progress 字段变化

#### TC-P4-007: 评估报告
- **步骤**: 完成后 `GET /api/evaluation/report`
- **预期**: 返回 latest batch 的完整报告
- **验证**: 包含 precisionAtK, recall, mrr, hitRate, avgLatencyMs, results

#### TC-P4-008: 评估历史
- **步骤**: `GET /api/evaluation/history`
- **预期**: 返回最近 20 条 completed batch
- **验证**: 按 evaluated_at DESC

#### TC-P4-009: 测试集 CRUD
- **步骤**: 创建 → 查询 → 更新 → 删除测试集
- **预期**: 每步操作成功
- **验证**: REST API

#### TC-P4-010: 测试用例 CRUD
- **步骤**: 在测试集下创建、查询、更新、删除用例
- **预期**: 操作成功
- **验证**: `evaluation_testcase` 数据

#### TC-P4-011: 评估结果命中判断
- **步骤**: 分析 `evaluation_result` 中的 hit 字段
- **预期**: expected_files 在 retrieved_files 中时 hit=true
- **验证**: 结果数据正确

#### TC-P4-012: 指标计算
- **步骤**: 完成后检查 precision_at_k, recall, mrr
- **预期**: 值为 [0, 1] 范围
- **验证**: 数值在 0-1 之间

#### TC-P4-013: 空测试集拒绝执行
- **步骤**: `POST /api/evaluation/run { testsetId: empty }`
- **预期**: 返回 400，错误信息
- **验证**: `{ "error": "测试集为空，无法执行评估" }`

#### TC-P4-014: 无效 testsetId
- **步骤**: `POST /api/evaluation/run { testsetId: "invalid" }`
- **预期**: 返回 400
- **验证**: `{ "error": "testsetId 格式非法" }`

#### TC-P4-015: 测试集导出
- **步骤**: `GET /api/evaluation/testset/{id}/export`
- **预期**: 返回 JSON 文件下载
- **验证**: Content-Disposition 头包含 .json

#### TC-P4-016: 测试集导入
- **步骤**: `POST /api/evaluation/testset/import` 上传 JSON 文件
- **预期**: 创建新测试集和用例
- **验证**: 导入的用例数匹配

---

### 前端测试（Playwright）

#### TC-P4-F01: 评估历史趋势图
- **步骤**: 切换到评估页面
- **预期**: ECharts 折线图显示最近评估趋势
- **验证**: `.echarts-instance` 渲染成功

#### TC-P4-F02: 运行评估按钮
- **步骤**: 选择测试集 → 设置 K → 点击"运行评估"
- **预期**: 进度条出现，completedCases 递增
- **验证**: 进度区域更新

#### TC-P4-F03: 取消评估
- **步骤**: 运行中点击"取消"
- **预期**: 状态变为已取消
- **验证**: UI 显示 cancelled

#### TC-P4-F04: 评估报告展示
- **步骤**: 完成后查看报告区域
- **预期**: 显示 Precision@K、Recall、MRR、Hit Rate
- **验证**: 指标卡片显示数值

#### TC-P4-F05: 测试集管理
- **步骤**: 创建测试集 → 添加用例 → 导出 → 导入
- **预期**: 全流程在 UI 正常完成
- **验证**: 用例列表更新

---

## P5 — Agent 工具调用

### 后端 API 测试

#### TC-P5-001: Agent 开关关闭时无工具注入
- **步骤**: `enableToolCalling=false`，发送消息
- **预期**: system prompt 不含工具描述
- **验证**: log 中 systemPrompt 无"=== 可用工具 ==="

#### TC-P5-002: Agent 开关开启时工具注入
- **步骤**: `enableToolCalling=true`，发送消息
- **预期**: system prompt 含 TOOL_DESCRIPTIONS
- **验证**: log 中 prompt 包含工具说明

#### TC-P5-003: 流式路径注册工具
- **步骤**: `enableToolCalling=true`, `supportsStreaming=true`，调用 `chat()`
- **预期**: `spec.tools(agentToolsProvider.getObject())` 执行
- **验证**: 不抛异常，工具可用

#### TC-P5-004: 非流式路径注册工具
- **步骤**: `enableToolCalling=true`, `supportsStreaming=false`，调用 `chat()`
- **预期**: `spec.tools()` 执行，`call().content()` 返回
- **验证**: 正常响应

#### TC-P5-005: Agent 开关部分更新
- **步骤**: PUT `/api/llm-configs/{id}` 只传 `enableToolCalling` 字段
- **预期**: 只更新该字段，其余不变
- **验证**: safeCopy 只更新非 null 字段

#### TC-P5-006: searchKnowledge 工具
- **步骤**: 调用 `searchKnowledge("xxx")`
- **预期**: 返回检索结果（最多 5 条），格式 `[序号] [类型] 签名 (路径)\n内容`
- **验证**: 结果格式正确

#### TC-P5-007: readFile 正常读取
- **步骤**: `readFile` 传入项目内有效文件路径
- **预期**: 返回文件内容（截断到 8000 字符）
- **验证**: 返回内容与文件一致

#### TC-P5-008: readFile 路径穿越防护
- **步骤**: `readFile("../../etc/passwd")`
- **预期**: 拒绝访问，返回错误信息
- **验证**: 返回 "不在允许目录下" 或类似错误

#### TC-P5-009: readFile 敏感文件拦截
- **步骤**: `readFile(".env")`、`readFile("credentials.json")`
- **预期**: 返回 "该文件类型被安全策略禁止读取"
- **验证**: isBlockedFile 匹配扩展名和文件名

#### TC-P5-010: listDirectory 正常列目录
- **步骤**: `listDirectory("src/main/java")`
- **预期**: 返回目录下的文件和子目录（最多 50 条）
- **验证**: 结果包含子目录名

#### TC-P5-011: listDirectory 路径穿越防护
- **步骤**: `listDirectory("../../etc")`
- **预期**: 拒绝访问
- **验证**: 返回错误

#### TC-P5-012: getKnowledgeBaseStats
- **步骤**: 调用 `getKnowledgeBaseStats()`
- **预期**: 返回项目列表和 chunk 数量
- **验证**: 结果包含统计信息

#### TC-P5-013: AgentToolMetadata 收集
- **步骤**: 执行工具调用后检查 AgentToolMetadata
- **预期**: collectAndClear() 返回工具调用记录
- **验证**: List<ToolCallRecord> 非空，包含 toolName, args, durationMs

#### TC-P5-014: AgentToolMetadata ThreadLocal 隔离
- **步骤**: 两个并发请求各调用不同工具
- **预期**: 各自只收集自己的元数据
- **验证**: 无交叉污染

#### TC-P5-015: 工具调用异常不影响对话
- **步骤**: 模拟工具内部异常
- **预期**: LLM 收到错误消息，继续对话
- **验证**: 流不中断

---

### 前端测试（Playwright）

#### TC-P5-F01: Agent 开关切换
- **步骤**: 在 LLM 设置表格中切换 Agent 开关
- **预期**: 开关状态更新，API 调用 PUT `/api/llm-configs/{id}`
- **验证**: 网络请求存在

#### TC-P5-F02: Agent 开关开启后工具指示器显示
- **步骤**: 开启 Agent 开关 → 发送问题 → 等待响应
- **预期**: 消息上方显示工具调用标签（蓝色药丸 + 图标 + 耗时）
- **验证**: `.tool-indicator` 存在且包含工具名

#### TC-P5-F03: 工具指示器跨 Markdown 渲染保留
- **步骤**: 开启 Agent → 发送问题 → 观察中间渲染过程
- **预期**: Markdown 重新渲染时工具指示器不被清除
- **验证**: `.tool-indicator` 始终在响应中

#### TC-P5-F04: Agent 关闭后无工具调用
- **步骤**: 关闭 Agent 开关 → 发送问题
- **预期**: 无工具指示器，LLM 不使用工具
- **验证**: 响应中无 toolMetadata

#### TC-P5-F05: 多个工具调用指示
- **步骤**: 问题同时触发多个工具（如先 searchKnowledge 再 readFile）
- **预期**: 显示多个工具标签
- **验证**: `.tool-indicator` 内有多个 `.inline-flex` 元素

#### TC-P5-F06: 工具耗时显示
- **步骤**: 观察工具指示器
- **预期**: 每个标签显示 `XXXms`
- **验证**: 文本包含 `\d+ms` 格式

#### TC-P5-F07: 多轮对话 + 工具调用
- **步骤**: 连续发送 3 条需要使用工具的问题
- **预期**: 每轮工具调用独立显示，历史消息正确保存
- **验证**: 侧边栏切换会话显示完整历史

---

## P5+ — 在线反馈

### 后端 API 测试

#### TC-FB-001: 提交正面反馈
- **步骤**: `POST /api/feedback { qaHistoryId, rating: 1 }`
- **预期**: 返回 200, `{ "status": "ok" }`
- **验证**: `qa_feedback` 表新增记录，rating=1

#### TC-FB-002: 提交负面反馈
- **步骤**: `POST /api/feedback { qaHistoryId, rating: -1, comment: "回答错误" }`
- **预期**: 返回 200, 记录含 comment
- **验证**: comment 字段正确

#### TC-FB-003: 重复提交覆盖
- **步骤**: 对同一 qaHistoryId 再次提交反馈
- **预期**: 更新已有记录，非新增
- **验证**: COUNT 不变，rating 更新

#### TC-FB-004: 无效 rating 拒绝
- **步骤**: `POST /api/feedback { qaHistoryId, rating: 0 }`
- **预期**: 返回 400
- **验证**: `{ "error": "rating 只能为 1 或 -1" }`

#### TC-FB-005: 缺少 qaHistoryId
- **步骤**: `POST /api/feedback { rating: 1 }`
- **预期**: 返回 400
- **验证**: `{ "error": "qaHistoryId 必填" }`

#### TC-FB-006: 反馈统计
- **步骤**: 提交多条反馈后 `GET /api/feedback/stats`
- **预期**: 返回 total, positive, negative, positiveRate
- **验证**: positiveRate = positive / total * 100

#### TC-FB-007: 低分问答列表
- **步骤**: `GET /api/feedback/low-quality`
- **预期**: 返回 rating=-1 的问答列表（最多 20 条）
- **验证**: 每条含 question, answer, comment

#### TC-FB-008: qaHistoryId 格式非法
- **步骤**: `POST /api/feedback { qaHistoryId: "invalid" }`
- **预期**: 返回 400
- **验证**: `{ "error": "qaHistoryId 格式非法" }`

### 前端测试（Playwright）

#### TC-FB-F01: 正面反馈提交
- **步骤**: 完成对话 → 点击 👍 按钮
- **预期**: 按钮变为"感谢反馈 👍"
- **验证**: `.fb-done` 类出现

#### TC-FB-F02: 负面反馈提交
- **步骤**: 点击 👎 按钮
- **预期**: 按钮变为"感谢反馈 👎"
- **验证**: 同上

#### TC-FB-F03: 反馈按钮只显示一次
- **步骤**: 提交反馈后检查
- **预期**: 无重复按钮
- **验证**: `querySelector('.feedback-bar')` 只有一个

#### TC-FB-F04: 反馈统计页面
- **步骤**: 导航到反馈统计区域
- **预期**: 显示总数和满意率
- **验证**: DOM 包含统计数据

---

## 测试数据准备

### 知识库数据（用户已准备）

```
测试数据路径: D:\HeXin\insolu\code_temp\
包含 32 个文件，覆盖多语言和多类型：
  ├── Java:  DataProcessor.java, OrderService.java, UserManager.java
  ├── Python: config_loader.py, data_analyzer.py, task_scheduler.py
  ├── JS:     httpClient.js, stateManager.js, validator.js
  ├── Vue:    DataTable.vue, OrderForm.vue, ProductList.vue
  ├── C#:     InventoryController.cs, PaymentService.cs, ReportGenerator.cs
  ├── SQL:    procedures.sql, queries.sql, schema.sql
  ├── HTML:   dashboard.html, login.html, profile.html
  ├── CSS:    components.css, layout.css, reset.css
  ├── MD:     api-design.md, project-architecture.md, python-notes.md
  ├── XML:    config.xml, data.xml, menu.xml
  ├── PDF:    20210101 享印畅链文印管理系统部署服务器推荐配置.pdf
  ├── XLSX:   2022年菱王文印业务运行状况及服务器配置建议.xlsx
  └── PPTX:   客户案例.pptx
```

**入库命令**：
```bash
curl -X POST http://localhost:8080/api/ingestion/process \
  -H "Content-Type: application/json" \
  -d '{"path": "D:\\HeXin\\insolu\\code_temp", "projectName": "code_temp"}'
```

### 建议测试查询（基于实际数据）

| 查询意图 | 示例查询 | 期望命中文件 |
|---------|---------|------------|
| Java 类检索 | `OrderService 中有哪些公开方法？` | `OrderService.java` |
| Python 脚本 | `task_scheduler 如何调度任务？` | `task_scheduler.py` |
| SQL 表结构 | `schema.sql 中定义了哪些表？` | `schema.sql` |
| API 设计 | `系统的 API 设计规范是什么？` | `api-design.md` |
| 前端组件 | `DataTable 组件如何传递数据？` | `DataTable.vue` |
| 系统架构 | `项目的整体架构是怎样的？` | `project-architecture.md` |
| 多语言混合 | `系统的技术栈包含哪些语言？` | 多个文件 |
| 文档类型 | `服务器部署需要什么配置？` | PDF 文件 |
| 业务分析 | `2022 年业务运行状况如何？` | XLSX 文件 |

### 种子测试集建议（基于 code_temp 数据）

```json
[
  {
    "question": "OrderService 中有哪些公开方法？",
    "expected_files": ["OrderService.java"],
    "tags": ["Java", "代码结构"]
  },
  {
    "question": "task_scheduler 如何调度任务？",
    "expected_files": ["task_scheduler.py"],
    "tags": ["Python", "调度"]
  },
  {
    "question": "schema.sql 中定义了哪些表？",
    "expected_files": ["schema.sql"],
    "tags": ["SQL", "表结构"]
  },
  {
    "question": "系统的 API 设计规范是什么？",
    "expected_files": ["api-design.md"],
    "tags": ["文档", "API"]
  },
  {
    "question": "DataTable 组件如何传递数据？",
    "expected_files": ["DataTable.vue"],
    "tags": ["Vue", "前端"]
  },
  {
    "question": "项目的整体架构是怎样的？",
    "expected_files": ["project-architecture.md"],
    "tags": ["架构", "文档"]
  },
  {
    "question": "系统的技术栈包含哪些语言？",
    "expected_files": ["DataProcessor.java", "config_loader.py", "httpClient.js"],
    "tags": ["多语言"]
  },
  {
    "question": "服务器部署需要什么配置？",
    "expected_files": ["20210101 享印畅链文印管理系统部署服务器推荐配置.pdf"],
    "tags": ["部署", "PDF"]
  },
  {
    "question": "2022 年业务运行状况如何？",
    "expected_files": ["2022年菱王文印业务运行状况及服务器配置建议.xlsx"],
    "tags": ["业务", "Excel"]
  },
  {
    "question": "validator 中包含哪些验证规则？",
    "expected_files": ["validator.js"],
    "tags": ["JavaScript", "验证"]
  }
]
```

### 测试用 LLM 配置
- 建议使用支持 tool calling 的模型（如通过 Ollama 的 `llama3` 或 API 的 `gpt-4o-mini`）
- 测试前确保至少有一个激活的 LLM 模型

---

## 测试执行顺序

```
1. 先执行 P0（对话历史）—— 其他功能依赖会话管理
2. 并行执行 P1（检索）和 P3（查询改写）—— 都依赖 RAG 管线
3. P2（Reranking）—— 依赖 P1 有数据
4. P4（评估）—— 依赖 P1/P2/P3 检索正确
5. P5（Agent）—— 依赖 P0 + P1
6. P5+（反馈）—— 依赖 P0
```

## 备注

- **流式测试注意事项**：SSE 流需要等待所有 token 到达，使用 `response.body` reader 完整消费
- **异步任务处理**：评估是 VirtualThread 异步，需要轮询或等待
- **数据库清理**：每个测试用例后清理测试数据（或使用 @Transactional rollback）
- **Mock 策略**：外部 LLM API 调用建议在单元测试中 mock，集成测试中允许真实调用
- **性能基线**：检索 < 2s，rerank < 10s，评估 < 60s（单个 batch）
