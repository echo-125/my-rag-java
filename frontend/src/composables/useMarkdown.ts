import MarkdownIt from 'markdown-it'
import { sanitizeHtml } from '@/utils/sanitize'

/**
 * Markdown 渲染器
 * 硬约束：html: false — 禁止原生 HTML 标签解析
 * 防止 LLM 输出的 <script> 等标签被直接保留
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
})

// 外链处理插件：添加 target="_blank" rel="noopener noreferrer"
function externalLinkPlugin(md: MarkdownIt) {
  const defaultRender = md.renderer.rules.link_open ||
    ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
  md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    tokens[idx].attrSet('target', '_blank')
    tokens[idx].attrSet('rel', 'noopener noreferrer')
    return defaultRender(tokens, idx, options, env, self)
  }
}

md.use(externalLinkPlugin)

/**
 * 渲染 Markdown 文本为清洗后的 HTML
 * 完整管线：markdown-it.render() → DOMPurify.sanitize()
 */
export function renderMarkdown(text: string): string {
  const raw = md.render(text)
  return sanitizeHtml(raw)
}

/** 获取底层 markdown-it 实例（用于插件扩展） */
export function getMarkdownIt(): MarkdownIt {
  return md
}
