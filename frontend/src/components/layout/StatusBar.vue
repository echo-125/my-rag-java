<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NSelect, NButton } from 'naive-ui'
import { useChatStore } from '@/stores/chat'
import { getRagConfigs } from '@/api/settings'
import { getStats } from '@/api/dashboard'

const store = useChatStore()
const selectedModel = ref('')

const bm25On = ref(false)
const rerankOn = ref(false)
const qrOn = ref(false)
const totalChunks = ref(0)
const projectCount = ref(0)

async function loadStatusBar() {
  try {
    const [configs, stats] = await Promise.all([
      getRagConfigs().catch(() => []),
      getStats().catch(() => null),
    ])
    const getVal = (key: string) => configs.find(c => c.key === key)?.value
    bm25On.value = getVal('enable_bm25') === 'true'
    qrOn.value = getVal('enable_query_rewrite') === 'true'
    rerankOn.value = getVal('enable_reranking') === 'true'
    if (stats) {
      totalChunks.value = stats.totalChunks
      projectCount.value = stats.projectCount
    }
  } catch {}
}

onMounted(async () => {
  await store.loadModels()
  await loadStatusBar()
})
</script>

<template>
  <div class="flex items-center gap-2 px-4 py-1.5 text-[11px] border-b font-mono whitespace-nowrap flex-shrink-0 overflow-x-auto select-none"
    style="background: var(--bg-elevated); border-color: var(--border); color: var(--text-3)">
    <span class="flex items-center gap-1">
      <span style="color: var(--text-2)">LLM</span>
      <span style="color: var(--text-1)">{{ store.models.find(m => m.id === selectedModel)?.name || '加载中...' }}</span>
    </span>
    <span style="color: var(--border)">│</span>
    <span class="flex items-center gap-1">
      <span style="color: var(--text-2)">SESSION</span>
      <span style="color: var(--text-1)">{{ store.currentSessionId ? store.currentSessionId.substring(0, 12) + '...' : '—' }}</span>
    </span>
    <span style="color: var(--border)">│</span>
    <span class="flex items-center gap-1">
      <span style="color: var(--text-2)">CHUNKS</span>
      <span style="color: var(--text-1)">{{ totalChunks }}</span>
    </span>
    <span style="color: var(--border)">│</span>
    <span class="flex items-center gap-1">
      <span style="color: var(--text-2)">PROJECTS</span>
      <span style="color: var(--text-1)">{{ projectCount }}</span>
    </span>
    <span style="color: var(--border)">│</span>
    <span class="px-1.5 py-0.5 rounded text-[10px] font-bold" :style="{ background: bm25On ? '#059669' : 'var(--bg-elevated)', color: bm25On ? 'white' : 'var(--text-3)', border: bm25On ? 'none' : '1px solid var(--border)' }">
      BM25: {{ bm25On ? 'ON' : 'OFF' }}
    </span>
    <span class="px-1.5 py-0.5 rounded text-[10px] font-bold" :style="{ background: rerankOn ? '#059669' : 'var(--bg-elevated)', color: rerankOn ? 'white' : 'var(--text-3)', border: rerankOn ? 'none' : '1px solid var(--border)' }">
      RERANK: {{ rerankOn ? 'ON' : 'OFF' }}
    </span>
    <span class="px-1.5 py-0.5 rounded text-[10px] font-bold" :style="{ background: qrOn ? '#059669' : 'var(--bg-elevated)', color: qrOn ? 'white' : 'var(--text-3)', border: qrOn ? 'none' : '1px solid var(--border)' }">
      QR: {{ qrOn ? 'ON' : 'OFF' }}
    </span>

    <span class="ml-auto flex items-center gap-2 flex-shrink-0">
      <NSelect
        v-model:value="selectedModel"
        :options="store.models.map(m => ({ label: m.name, value: m.id }))"
        size="tiny"
        style="width: 160px"
        placeholder="加载模型中..."
      />
      <NButton size="tiny" quaternary @click="store.resetChat()">+ 新会话</NButton>
    </span>
  </div>
</template>
