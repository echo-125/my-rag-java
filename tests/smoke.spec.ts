import { test, expect } from '@playwright/test'

test.describe('Smoke Test — 前端迁移验证', () => {

  test.beforeEach(async ({ page }) => {
    await page.goto('/')
  })

  test('1. 首页加载 — 显示 Vue 应用', async ({ page }) => {
    await expect(page.locator('#app')).toBeVisible()
    // 侧栏应存在
    await expect(page.locator('aside').first()).toBeVisible()
  })

  test('2. 对话页 — 默认路由', async ({ page }) => {
    // hash 路由默认 /chat
    await expect(page).toHaveURL(/\/#\/chat/)
    // 输入框存在
    await expect(page.locator('textarea[placeholder*="查询"]')).toBeVisible()
  })

  test('3. 仪表盘 — 导航跳转 + API 数据', async ({ page }) => {
    await page.click('text=仪表盘')
    await expect(page).toHaveURL(/\/#\/dashboard/)
    // 等待 API 数据加载（StatCard 渲染）
    await page.waitForSelector('text=总文档块', { timeout: 5000 })
    await expect(page.locator('text=总文档块').first()).toBeVisible()
  })

  test('4. 设置页 — 导航跳转 + API 数据', async ({ page }) => {
    await page.click('text=设置')
    await expect(page).toHaveURL(/\/#\/settings/)
    // 等待 API 数据加载（最多 5 秒）
    await page.waitForSelector('text=RAG 策略', { timeout: 5000 })
    await expect(page.locator('text=RAG 策略与切片配置')).toBeVisible()
  })

  test('5. 入库页 — 导航跳转', async ({ page }) => {
    await page.click('text=文档入库')
    await expect(page).toHaveURL(/\/#\/ingestion/)
    await expect(page.locator('text=目标项目配置')).toBeVisible()
  })

  test('6. 评估页 — 导航跳转', async ({ page }) => {
    await page.click('text=评估')
    await expect(page).toHaveURL(/\/#\/evaluation/)
    await expect(page.locator('text=评估中心')).toBeVisible()
  })

  test('7. API 连通性 — dashboard stats', async ({ page }) => {
    const response = await page.request.get('/api/dashboard/stats')
    expect(response.ok()).toBe(true)
    const data = await response.json()
    expect(data).toHaveProperty('totalChunks')
    expect(data).toHaveProperty('fileCount')
  })

  test('8. 侧栏折叠', async ({ page }) => {
    const sidebar = page.locator('aside').first()
    const collapseBtn = sidebar.locator('button').last()
    await collapseBtn.click()
    // 等待动画
    await page.waitForTimeout(400)
    // 侧栏应变窄（w-14 = 56px）
    const width = await sidebar.evaluate(el => el.getBoundingClientRect().width)
    expect(width).toBeLessThan(100)
  })

})
