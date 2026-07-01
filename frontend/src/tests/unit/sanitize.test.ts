import { describe, it, expect } from 'vitest'
import { sanitizeHtml, sanitizeSvg } from '@/utils/sanitize'

describe('sanitizeHtml', () => {
  it('移除 <script> 标签', () => {
    const input = '<p>正常内容</p><script>alert("xss")</script>'
    const result = sanitizeHtml(input)
    expect(result).toContain('<p>正常内容</p>')
    expect(result).not.toContain('<script>')
    expect(result).not.toContain('alert')
  })

  it('移除 onerror 等事件属性', () => {
    const input = '<img src="x" onerror="alert(1)">'
    const result = sanitizeHtml(input)
    expect(result).not.toContain('onerror')
    expect(result).not.toContain('alert')
  })

  it('保留 <table> 标签', () => {
    const input = '<table><tr><td>单元格</td></tr></table>'
    const result = sanitizeHtml(input)
    expect(result).toContain('<table>')
    expect(result).toContain('<td>')
  })

  it('保留 <code> 标签', () => {
    const input = '<pre><code class="language-java">System.out.println("hello");</code></pre>'
    const result = sanitizeHtml(input)
    expect(result).toContain('<code')
    expect(result).toContain('System.out.println')
  })

  it('保留 Markdown 渲染的标准输出', () => {
    const input = '<h1>标题</h1><p>段落</p><ul><li>列表项</li></ul><blockquote>引用</blockquote>'
    const result = sanitizeHtml(input)
    expect(result).toContain('<h1>')
    expect(result).toContain('<p>')
    expect(result).toContain('<ul>')
    expect(result).toContain('<blockquote>')
  })

  it('移除 javascript: 协议链接', () => {
    const input = '<a href="javascript:alert(1)">点击</a>'
    const result = sanitizeHtml(input)
    expect(result).not.toContain('javascript:')
  })
})

describe('sanitizeSvg', () => {
  it('保留标准 SVG 结构', () => {
    const input = '<svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="40" fill="red"/></svg>'
    const result = sanitizeSvg(input)
    expect(result).toContain('<svg')
    expect(result).toContain('<circle')
  })

  it('移除 SVG 中的 script 标签', () => {
    const input = '<svg><script>alert(1)</script><rect width="10" height="10"/></svg>'
    const result = sanitizeSvg(input)
    expect(result).not.toContain('<script>')
    expect(result).toContain('<rect')
  })
})
