import { createHighlighter } from 'shiki'
import markdownit from 'markdown-it'
import { fromHighlighter } from '@shikijs/markdown-it'

let highlighterPromise: ReturnType<typeof createHighlighter> | null = null
let mdInstance: ReturnType<typeof markdownit> | null = null

function getHighlighter() {
  if (!highlighterPromise) {
    highlighterPromise = createHighlighter({
      themes: ['vitesse-dark', 'vitesse-light'],
      langs: ['javascript', 'typescript', 'python', 'bash', 'json', 'yaml', 'markdown', 'html', 'css', 'java', 'sql', 'mermaid'],
    })
  }
  return highlighterPromise
}

async function getMarkdownIt() {
  if (mdInstance) return mdInstance
  const hl = await getHighlighter()
  const md = markdownit({
    html: false,
    linkify: true,
    typographer: true,
    breaks: true,
  })
  md.use(fromHighlighter(hl, {
    themes: { dark: 'vitesse-dark', light: 'vitesse-light' },
  }))
  mdInstance = md
  return md
}

export function useMarkdown() {
  const rendered = ref('')
  const loading = ref(true)

  async function render(content: string) {
    loading.value = true
    const md = await getMarkdownIt()
    let html = md.render(content)

    // 替换 mermaid 代码块为占位符
    html = html.replace(
      /<pre><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/g,
      '<div class="mermaid-placeholder" data-mermaid="$1"></div>',
    )

    rendered.value = html
    loading.value = false
  }

  return { rendered, loading, render }
}
