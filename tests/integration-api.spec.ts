import { test, expect } from '@playwright/test';

const BASE = 'http://localhost:8080';

/**
 * 集成测试：需要服务已启动且测试数据已入库。
 * 先执行 node tests/setup-test-data.js 入库数据。
 */

// 生成合法 UUID
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0;
    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
  });
}

// ═══════════════════════════════════════════════════
//  P0 — 对话历史集成测试
// ═══════════════════════════════════════════════════

test.describe('P0 — 对话历史集成', () => {

  test('TC-P0-F01: 会话列表加载', async ({ page }) => {
    await page.goto(BASE);
    await page.waitForSelector('#sessionList', { state: 'attached', timeout: 10000 });

    // 验证会话列表 API 返回正确
    const resp = await page.request.get(`${BASE}/api/sessions`);
    expect(resp.ok()).toBeTruthy();
    const sessions = await resp.json();
    expect(Array.isArray(sessions)).toBeTruthy();
  });

  test('TC-P0-F02: 新建会话后欢迎页', async ({ page }) => {
    await page.goto(BASE);
    await page.waitForSelector('#chatWelcome', { timeout: 10000 });
    await expect(page.locator('#chatWelcome h2')).toContainText('有什么可以帮你的');
  });
});

// ═══════════════════════════════════════════════════
//  P1 — 混合检索集成测试
// ═══════════════════════════════════════════════════

test.describe('P1 — 混合检索集成', () => {

  test('TC-P1-F01: chat/stream 端点可访问', async ({ page }) => {
    // 验证 chat/stream 端点存在且接受 POST 请求
    // 无激活 LLM 时可能返回错误，但端点本身应可达
    const resp = await page.request.post(`${BASE}/api/chat/stream`, {
      data: { query: 'OrderService 中有哪些公开方法？', modelKey: '', sessionId: uuid() }
    });
    // 端点应返回 200（流式）或错误码（无 LLM），但不应是 404
    expect(resp.status()).not.toBe(404);
  });
});

// ═══════════════════════════════════════════════════
//  P4 — 评估体系集成测试
// ═══════════════════════════════════════════════════

test.describe('P4 — 评估体系集成', () => {

  test('TC-P4-F05: 测试集列表加载', async ({ page }) => {
    const resp = await page.request.get(`${BASE}/api/evaluation/testset`);
    expect(resp.ok()).toBeTruthy();
    const testsets = await resp.json();
    expect(Array.isArray(testsets)).toBeTruthy();
  });

  test('TC-P4-F04: 评估页面指标卡片', async ({ page }) => {
    await page.goto(BASE);
    await page.click('.tab-btn[onclick*="evaluation"]');
    await page.waitForSelector('#evalMetrics', { timeout: 10000 });

    // 指标卡片应存在且包含文本（可能是 "—" 或数值）
    await expect(page.locator('#metricP\\@K')).toBeAttached();
    await expect(page.locator('#metricRecall')).toBeAttached();
    await expect(page.locator('#metricMRR')).toBeAttached();
    await expect(page.locator('#metricHitRate')).toBeAttached();
  });
});

// ═══════════════════════════════════════════════════
//  P5 — Agent 工具调用集成测试
// ═══════════════════════════════════════════════════

test.describe('P5 — Agent 工具调用集成', () => {

  test('TC-P5-F01: LLM 配置表格加载', async ({ page }) => {
    const resp = await page.request.get(`${BASE}/api/llm-configs`);
    expect(resp.ok()).toBeTruthy();
    const configs = await resp.json();
    expect(Array.isArray(configs)).toBeTruthy();
  });

  test('TC-P5-F02: Agent 开关状态', async ({ page }) => {
    await page.goto(BASE);
    await page.click('.tab-btn[onclick*="settings"]');
    await page.waitForSelector('#llmTable', { timeout: 10000 });

    // 验证 LLM 配置表格有 Agent 列
    const header = page.locator('th:has-text("Agent")');
    await expect(header).toBeVisible();
  });
});

// ═══════════════════════════════════════════════════
//  P5+ — 在线反馈集成测试
// ═══════════════════════════════════════════════════

test.describe('P5+ — 在线反馈集成', () => {

  test('TC-FB-F04: 反馈统计 API', async ({ page }) => {
    const resp = await page.request.get(`${BASE}/api/feedback/stats`);
    expect(resp.ok()).toBeTruthy();
    const stats = await resp.json();
    expect(stats).toHaveProperty('total');
    expect(stats).toHaveProperty('positive');
    expect(stats).toHaveProperty('negative');
    expect(stats).toHaveProperty('positiveRate');
  });
});

// ═══════════════════════════════════════════════════
//  RAG 配置集成测试
// ═══════════════════════════════════════════════════

test.describe('RAG 配置', () => {

  test('配置列表加载', async ({ page }) => {
    const resp = await page.request.get(`${BASE}/api/configs`);
    expect(resp.ok()).toBeTruthy();
    const configs = await resp.json();
    expect(Array.isArray(configs)).toBeTruthy();
    // 应包含 enable_bm25 等配置
    const keys = configs.map((c: any) => c.key);
    expect(keys).toContain('enable_bm25');
  });

  test('设置页 RAG 配置区域', async ({ page }) => {
    await page.goto(BASE);
    await page.click('.tab-btn[onclick*="settings"]');
    await page.waitForSelector('#ragConfig', { timeout: 10000 });
    await expect(page.locator('#ragConfig')).toBeAttached();
  });
});
