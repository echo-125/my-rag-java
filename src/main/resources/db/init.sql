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

-- llm_config、project_config、qa_history、embedding_config 表由 JPA (ddl-auto: update) 自动创建。

-- ============================================================
-- rag_config 全局 RAG 配置表（切分参数 + 清洗参数）
-- 由应用启动时 RagConfigService.initializeDefaults() 自动填充默认值
-- ============================================================
CREATE TABLE IF NOT EXISTS rag_config (
    config_key   VARCHAR(100) PRIMARY KEY,
    config_value TEXT         NOT NULL,
    description  VARCHAR(255),
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
