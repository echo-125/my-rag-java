-- ============================================================
-- MY RAG 数据库初始化脚本
-- 执行方式: psql -U postgres -f src/main/resources/db/init.sql
-- ============================================================

-- 1. 创建数据库（如不存在）
SELECT 'CREATE DATABASE my_rag'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'my_rag')\gexec

-- 2. 连接到 my_rag 数据库
\c my_rag

-- 3. 安装 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- document_chunks 表由 LangChain4j PgVectorEmbeddingStore
-- 在应用启动时自动创建（createTable=true）。
--
-- 旧表如存在，请先执行：
--   DROP TABLE IF EXISTS document_chunks;
--
-- 然后重启应用，LangChain4j 会自动创建新表。
-- ============================================================

-- config_llm、config_project、config_embedding、config_reranking、qa_history 表由 JPA (ddl-auto: update) 自动创建。

-- ============================================================
-- config_rag 全局 RAG 配置表（切分参数 + 清洗参数）
-- 由应用启动时 RagConfigService.initializeDefaults() 自动填充默认值
-- ============================================================
CREATE TABLE IF NOT EXISTS config_rag (
    config_key   VARCHAR(100) PRIMARY KEY,
    config_value TEXT         NOT NULL,
    description  VARCHAR(255),
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 表列中文注释
-- JPA 创建表后，手动添加列注释以提升可读性。
-- 可重复执行，已存在的注释会被覆盖。
-- ============================================================

-- config_rag 表注释
COMMENT ON TABLE config_rag IS '全局 RAG 配置表 —— 切分参数、检索参数等';
COMMENT ON COLUMN config_rag.config_key IS '配置键，如 "max_segment_size"';
COMMENT ON COLUMN config_rag.config_value IS '配置值';
COMMENT ON COLUMN config_rag.description IS '配置描述';
COMMENT ON COLUMN config_rag.updated_at IS '更新时间';

-- config_llm 表注释
COMMENT ON TABLE config_llm IS 'LLM 配置表 —— 存储不同大语言模型的 API 连接信息';
COMMENT ON COLUMN config_llm.id IS '主键ID';
COMMENT ON COLUMN config_llm.name IS '配置名称，如 "DeepSeek-Coder"';
COMMENT ON COLUMN config_llm.model_name IS '模型名称，如 "deepseek-coder"';
COMMENT ON COLUMN config_llm.base_url IS 'API 基础 URL';
COMMENT ON COLUMN config_llm.api_key IS 'API 密钥（AES 加密存储）';
COMMENT ON COLUMN config_llm.api_format IS 'API 格式：openai_chat_completions / anthropic_messages';
COMMENT ON COLUMN config_llm.is_active IS '是否激活';
COMMENT ON COLUMN config_llm.supports_streaming IS '是否支持流式请求（测试连接时自动检测）';
COMMENT ON COLUMN config_llm.enable_tool_calling IS '是否启用 Agent 工具调用（需模型支持 Function Calling）';
COMMENT ON COLUMN config_llm.created_at IS '创建时间';
COMMENT ON COLUMN config_llm.updated_at IS '更新时间';

-- config_embedding 表注释
COMMENT ON TABLE config_embedding IS 'Embedding 配置表 —— 存储向量化模型的连接信息';
COMMENT ON COLUMN config_embedding.id IS '主键ID';
COMMENT ON COLUMN config_embedding.name IS '配置名称，如 "本地 Ollama qwen3-embedding"';
COMMENT ON COLUMN config_embedding.provider IS 'Provider 类型：ollama / openai';
COMMENT ON COLUMN config_embedding.base_url IS 'API 基础 URL';
COMMENT ON COLUMN config_embedding.model_name IS '模型名称';
COMMENT ON COLUMN config_embedding.api_key IS 'API 密钥（Ollama 本地不需要，OpenAI 兼容 API 需要）';
COMMENT ON COLUMN config_embedding.dimension IS '向量维度';
COMMENT ON COLUMN config_embedding.is_active IS '是否激活';
COMMENT ON COLUMN config_embedding.created_at IS '创建时间';
COMMENT ON COLUMN config_embedding.updated_at IS '更新时间';

-- chat_session 表注释
COMMENT ON TABLE chat_session IS '聊天会话表 —— 持久化对话会话元数据';
COMMENT ON COLUMN chat_session.id IS '主键ID';
COMMENT ON COLUMN chat_session.title IS '会话标题（取首条用户消息前 30 字）';
COMMENT ON COLUMN chat_session.created_at IS '创建时间';
COMMENT ON COLUMN chat_session.updated_at IS '更新时间';

-- chat_message 表注释
COMMENT ON TABLE chat_message IS '聊天消息表 —— 持久化会话中的每条消息';
COMMENT ON COLUMN chat_message.id IS '主键ID';
COMMENT ON COLUMN chat_message.session_id IS '所属会话ID';
COMMENT ON COLUMN chat_message.role IS '角色：user / assistant';
COMMENT ON COLUMN chat_message.content IS '消息内容';
COMMENT ON COLUMN chat_message.created_at IS '创建时间';

-- config_project 表注释
COMMENT ON TABLE config_project IS '项目配置表 —— 持久化入库项目的路径配置';
COMMENT ON COLUMN config_project.id IS '主键ID';
COMMENT ON COLUMN config_project.name IS '项目名称';
COMMENT ON COLUMN config_project.path IS '本地路径';
COMMENT ON COLUMN config_project.status IS '状态：pending(待入库) / completed(已入库)';
COMMENT ON COLUMN config_project.ingested_at IS '入库完成时间';
COMMENT ON COLUMN config_project.description IS 'LLM 生成的项目简介';
COMMENT ON COLUMN config_project.created_at IS '创建时间';

-- qa_history 表注释
COMMENT ON TABLE qa_history IS '问答历史表 —— 持久化用户与 AI 的对话记录';
COMMENT ON COLUMN qa_history.id IS '主键ID';
COMMENT ON COLUMN qa_history.question IS '用户提问';
COMMENT ON COLUMN qa_history.answer IS 'AI 回答';
COMMENT ON COLUMN qa_history.model_name IS '使用的模型名称';
COMMENT ON COLUMN qa_history.created_at IS '创建时间';

-- config_reranking 表注释
COMMENT ON TABLE config_reranking IS 'Reranking 配置表 —— 存储 Reranking 模型的连接信息';
COMMENT ON COLUMN config_reranking.id IS '主键ID';
COMMENT ON COLUMN config_reranking.name IS '配置名称，如 "BGE-Reranker-v2-m3"';
COMMENT ON COLUMN config_reranking.provider IS '接入方式：ollama / api';
COMMENT ON COLUMN config_reranking.model_name IS '模型名称，如 "bge-reranker-v2-m3"';
COMMENT ON COLUMN config_reranking.base_url IS '服务地址（Ollama 地址或 API endpoint）';
COMMENT ON COLUMN config_reranking.api_key IS 'API 密钥（API 模式下使用）';
COMMENT ON COLUMN config_reranking.is_active IS '是否激活';
COMMENT ON COLUMN config_reranking.created_at IS '创建时间';
COMMENT ON COLUMN config_reranking.updated_at IS '更新时间';

-- evaluation_testset 表注释
COMMENT ON TABLE evaluation_testset IS '评估测试集表 —— 存储评估测试集的元数据';
COMMENT ON COLUMN evaluation_testset.id IS '主键ID';
COMMENT ON COLUMN evaluation_testset.name IS '测试集名称';
COMMENT ON COLUMN evaluation_testset.description IS '测试集描述';
COMMENT ON COLUMN evaluation_testset.created_at IS '创建时间';
COMMENT ON COLUMN evaluation_testset.updated_at IS '更新时间';

-- evaluation_testcase 表注释
COMMENT ON TABLE evaluation_testcase IS '评估测试用例表 —— 存储单个测试用例的问题和期望结果';
COMMENT ON COLUMN evaluation_testcase.id IS '主键ID';
COMMENT ON COLUMN evaluation_testcase.testset_id IS '所属测试集ID';
COMMENT ON COLUMN evaluation_testcase.question IS '测试问题';
COMMENT ON COLUMN evaluation_testcase.expected_files IS '期望匹配的文件列表（JSON数组）';
COMMENT ON COLUMN evaluation_testcase.tags IS '标签列表（JSON数组）';
COMMENT ON COLUMN evaluation_testcase.created_at IS '创建时间';

-- evaluation_batch 表注释
COMMENT ON TABLE evaluation_batch IS '评估批次表 —— 存储一次评估任务的执行状态和汇总结果';
COMMENT ON COLUMN evaluation_batch.id IS '主键ID';
COMMENT ON COLUMN evaluation_batch.testset_id IS '测试集ID';
COMMENT ON COLUMN evaluation_batch.config_snapshot IS '评估配置快照（JSON）';
COMMENT ON COLUMN evaluation_batch.status IS '状态：running / completed / failed';
COMMENT ON COLUMN evaluation_batch.total_cases IS '总测试用例数';
COMMENT ON COLUMN evaluation_batch.completed_cases IS '已完成测试用例数';
COMMENT ON COLUMN evaluation_batch.precision_at_k IS 'Precision@K 得分';
COMMENT ON COLUMN evaluation_batch.recall_score IS 'Recall 得分';
COMMENT ON COLUMN evaluation_batch.mrr IS 'MRR（平均倒数排名）得分';
COMMENT ON COLUMN evaluation_batch.hit_rate IS '命中率';
COMMENT ON COLUMN evaluation_batch.avg_latency_ms IS '平均延迟（毫秒）';
COMMENT ON COLUMN evaluation_batch.evaluated_at IS '评估完成时间';
COMMENT ON COLUMN evaluation_batch.error_message IS '错误信息';
COMMENT ON COLUMN evaluation_batch.cancelled IS '是否已取消';
COMMENT ON COLUMN evaluation_batch.created_at IS '创建时间';

-- evaluation_result 表注释
COMMENT ON TABLE evaluation_result IS '评估结果表 —— 存储单个测试用例的评估详情';
COMMENT ON COLUMN evaluation_result.id IS '主键ID';
COMMENT ON COLUMN evaluation_result.batch_id IS '评估批次ID';
COMMENT ON COLUMN evaluation_result.testcase_id IS '测试用例ID';
COMMENT ON COLUMN evaluation_result.question IS '测试问题';
COMMENT ON COLUMN evaluation_result.retrieved_files IS '检索到的文件列表（JSON数组）';
COMMENT ON COLUMN evaluation_result.expected_files IS '期望匹配的文件列表（JSON数组）';
COMMENT ON COLUMN evaluation_result.hit IS '是否命中期望文件';
COMMENT ON COLUMN evaluation_result.first_hit_rank IS '首次命中排名（1-based）';
COMMENT ON COLUMN evaluation_result.latency_ms IS '检索延迟（毫秒）';
COMMENT ON COLUMN evaluation_result.parse_warning IS '解析警告信息';
COMMENT ON COLUMN evaluation_result.created_at IS '创建时间';

-- qa_feedback 表注释
COMMENT ON TABLE qa_feedback IS '用户反馈表 —— 存储用户对AI回答的评价';
COMMENT ON COLUMN qa_feedback.id IS '主键ID';
COMMENT ON COLUMN qa_feedback.qa_history_id IS '关联的问答历史ID';
COMMENT ON COLUMN qa_feedback.rating IS '评分：1=👍, -1=👎';
COMMENT ON COLUMN qa_feedback.comment IS '用户评论';
COMMENT ON COLUMN qa_feedback.created_at IS '创建时间';
