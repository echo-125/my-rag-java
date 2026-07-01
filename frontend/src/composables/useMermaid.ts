import mermaid from 'mermaid'
import { sanitizeSvg } from '@/utils/sanitize'

// 模块级 counter — 跨组件共享，避免 ID 冲突
let counter = 0

// 初始化 Mermaid（仅一次）
let initialized = false
function ensureInit() {
  if (!initialized) {
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      theme: 'default',
    })
    initialized = true
  }
}

/**
 * 渲染单个 Mermaid 代码块为清洗后的 SVG HTML
 * 使用全局递增 ID 防止重复
 */
export async function renderMermaidCode(code: string): Promise<string> {
  ensureInit()
  const id = `mmd-${++counter}`
  try {
    const { svg } = await mermaid.render(id, code)
    return sanitizeSvg(svg)
  } catch {
    return '<div class="text-red-500 text-xs">图表渲染失败</div>'
  }
}

/**
 * 扫描容器中的 mermaid 代码块并替换为 SVG
 * 复刻 app.js:558-569 的逻辑，但使用 sanitizeSvg 二次清洗
 */
export async function renderMermaidInContainer(container: HTMLElement): Promise<void> {
  ensureInit()
  const blocks = container.querySelectorAll('pre code.language-mermaid')
  for (const block of Array.from(blocks)) {
    const code = block.textContent?.trim()
    if (!code) continue

    const wrapper = document.createElement('div')
    wrapper.className = 'my-4 flex justify-center mermaid-wrapper'

    block.parentNode?.replaceChild(wrapper, block)
    wrapper.innerHTML = await renderMermaidCode(code)
  }
}

/**
 * Vue composable 版本 — 在 v-html 渲染后调用
 * 配合 nextTick 使用
 */
export function useMermaid() {
  return { renderMermaidCode, renderMermaidInContainer }
}
