<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { useMarkdown } from '@/composables/useMarkdown'
import { useMermaid } from '@/composables/useMermaid'

const props = defineProps<{ content: string }>()

const { rendered, render, loading } = useMarkdown()
const { render: renderMermaid } = useMermaid()
const containerRef = ref<HTMLElement | null>(null)
const mermaidRendered = ref(false)

async function renderContent() {
  await render(props.content)
  await nextTick()
  renderMermaidBlocks()
}

async function renderMermaidBlocks() {
  if (!containerRef.value) return
  const blocks = containerRef.value.querySelectorAll<HTMLElement>('.mermaid-placeholder')
  for (const block of blocks) {
    const code = block.getAttribute('data-mermaid')
    if (code) {
      const svg = await renderMermaid(code, crypto.randomUUID())
      const wrapper = document.createElement('div')
      wrapper.innerHTML = svg
      block.parentNode?.replaceChild(wrapper, block)
    }
  }
  mermaidRendered.value = true
}

watch(() => props.content, renderContent, { immediate: true })
</script>

<template>
  <div
    ref="containerRef"
    class="markdown-body text-sm leading-relaxed text-default"
  >
    <div v-if="loading && !rendered" class="flex items-center gap-2 text-sm text-muted py-2">
      <UIcon name="lucide:loader-2" class="h-4 w-4 animate-spin" />
      渲染中...
    </div>
    <div v-else v-html="rendered" />
  </div>
</template>
