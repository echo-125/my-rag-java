#!/usr/bin/env node

/**
 * 集成测试前置脚本：
 * 1. 入库测试数据（POST /api/ingestion/process）
 * 2. 等待入库完成
 * 3. 验证知识库状态
 *
 * 使用方式：node tests/setup-test-data.js
 * 前提条件：服务已启动在 http://localhost:8080
 */

const BASE_URL = 'http://localhost:8080';
const TEST_DATA_PATH = 'D:\\HeXin\\insolu\\code_temp';
const PROJECT_NAME = 'code_temp';

async function waitForServer(maxRetries = 30, intervalMs = 2000) {
  for (let i = 0; i < maxRetries; i++) {
    try {
      const resp = await fetch(`${BASE_URL}/api/configs`);
      if (resp.ok) {
        console.log('✅ 服务已就绪');
        return true;
      }
    } catch (e) {
      // 服务未就绪，继续等待
    }
    console.log(`⏳ 等待服务启动... (${i + 1}/${maxRetries})`);
    await new Promise(r => setTimeout(r, intervalMs));
  }
  throw new Error('❌ 服务启动超时');
}

async function clearExistingData() {
  console.log('\n📦 清空旧测试数据...');
  try {
    const resp = await fetch(`${BASE_URL}/api/ingestion/chunks/${PROJECT_NAME}`, {
      method: 'DELETE'
    });
    if (resp.ok) {
      const data = await resp.json();
      console.log(`  已清空 ${data.deleted} 个旧 chunks`);
    }
  } catch (e) {
    console.log('  清空失败（可能无旧数据）:', e.message);
  }
}

async function ingestTestData() {
  console.log('\n📥 开始入库测试数据...');
  console.log(`  路径: ${TEST_DATA_PATH}`);
  console.log(`  项目名: ${PROJECT_NAME}`);

  const resp = await fetch(`${BASE_URL}/api/ingestion/process`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      projects: [{ name: PROJECT_NAME, path: TEST_DATA_PATH }],
      exts: ['.java', '.py', '.js', '.vue', '.cs', '.sql', '.html', '.css', '.md', '.xml']
    })
  });

  if (!resp.ok) {
    throw new Error(`入库请求失败: HTTP ${resp.status}`);
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let lastProgress = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      const t = line.trim();
      if (t.startsWith('data:') && t.length > 6) {
        try {
          const data = JSON.parse(t.substring(5));
          if (data.current !== undefined && data.total !== undefined) {
            const pct = data.progressPercentage || Math.floor((data.current / data.total) * 100);
            const progress = `  [${pct}%] ${data.current}/${data.total}`;
            if (progress !== lastProgress) {
              process.stdout.write('\r' + progress + '  ');
              lastProgress = progress;
            }
          }
          if (data.status === 'done') {
            console.log('\n✅ 入库完成');
          }
          if (data.status === 'error') {
            console.log('\n❌ 入库错误:', data.message);
          }
        } catch (e) {
          // 忽略解析错误
        }
      }
    }
  }
  console.log('');
}

async function verifyKnowledgeBase() {
  console.log('\n🔍 验证知识库状态...');

  // 检查 chunk 数量
  const countResp = await fetch(`${BASE_URL}/api/ingestion/chunks/${PROJECT_NAME}/count`);
  if (countResp.ok) {
    const data = await countResp.json();
    console.log(`  项目 "${PROJECT_NAME}" chunk 数量: ${data.count}`);
    if (data.count === 0) {
      console.log('  ⚠️ 警告：chunk 数量为 0，入库可能未成功');
    }
  }

  // 检查仪表盘统计
  const statsResp = await fetch(`${BASE_URL}/api/dashboard/stats`);
  if (statsResp.ok) {
    const stats = await statsResp.json();
    console.log(`  总 chunks: ${stats.totalChunks || 0}`);
    console.log(`  已索引文件: ${stats.fileCount || 0}`);
    console.log(`  项目数: ${stats.projectCount || 0}`);
  }
}

async function seedEvaluationTestset() {
  console.log('\n📋 检查评估测试集...');
  const resp = await fetch(`${BASE_URL}/api/evaluation/testset`);
  if (resp.ok) {
    const testsets = await resp.json();
    console.log(`  现有测试集数量: ${testsets.length}`);
    if (testsets.length > 0) {
      const ts = testsets[0];
      console.log(`  默认测试集: "${ts.name}" (${ts.caseCount} 条用例)`);
    }
  }
}

async function main() {
  console.log('═══════════════════════════════════════════');
  console.log('  RAG 集成测试数据准备');
  console.log('═══════════════════════════════════════════');

  try {
    await waitForServer();
    await clearExistingData();
    await ingestTestData();
    await verifyKnowledgeBase();
    await seedEvaluationTestset();

    console.log('\n═══════════════════════════════════════════');
    console.log('  ✅ 测试数据准备完成');
    console.log('═══════════════════════════════════════════');
  } catch (e) {
    console.error('\n❌ 测试数据准备失败:', e.message);
    process.exit(1);
  }
}

main();
