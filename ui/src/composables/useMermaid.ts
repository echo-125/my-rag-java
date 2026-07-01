import mermaid from 'mermaid'

let initialized = false

export function useMermaid() {
  async function init() {
    if (initialized) return
    mermaid.initialize({
      startOnLoad: false,
      theme: 'dark',
      securityLevel: 'loose',
    })
    initialized = true
  }

  async function render(code: string, id: string): Promise<string> {
    await init()
    try {
      const { svg } = await mermaid.render(`mermaid-${id}`, code)
      return svg
    } catch {
      return `<pre class="text-red-400">Mermaid 渲染失败</pre>`
    }
  }

  return { render }
}
