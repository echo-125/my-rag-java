<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const collapsed = ref(false)

interface Tab {
  name: string
  label: string
  icon: string
}

const tabs: Tab[] = [
  { name: 'chat', label: '对话', icon: 'chat' },
  { name: 'dashboard', label: '仪表盘', icon: 'dashboard' },
  { name: 'ingestion', label: '文档入库', icon: 'ingestion' },
  { name: 'evaluation', label: '评估', icon: 'evaluation' },
  { name: 'settings', label: '设置', icon: 'settings' },
]

function navigate(name: string) {
  router.push({ name })
}

function isActive(name: string) {
  return route.name === name
}
</script>

<template>
  <aside
    class="flex flex-col py-4 gap-2 flex-shrink-0 z-20 border-r transition-all duration-300"
    :class="collapsed ? 'w-14' : 'w-[180px]'"
    style="background: var(--bg-card); border-color: var(--border)"
  >
    <div class="flex items-center mb-4 px-2">
      <div
        class="w-8 h-8 rounded-lg flex items-center justify-center text-white font-bold text-xs flex-shrink-0"
        style="background: var(--c-primary)"
      >
        KB
      </div>
      <span
        v-if="!collapsed"
        class="ml-3 text-sm font-semibold whitespace-nowrap overflow-hidden"
        style="color: var(--text-2)"
      >
        知识库
      </span>
    </div>

    <button
      v-for="tab in tabs"
      :key="tab.name"
      @click="navigate(tab.name)"
      class="flex items-center px-3 py-2.5 mx-2 rounded-xl transition-colors"
      :class="isActive(tab.name) ? 'text-[var(--c-primary)] bg-[var(--bg-elevated)]' : 'text-[var(--text-3)] hover:bg-[var(--bg-elevated)]'"
      :title="tab.label"
    >
      <svg class="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          v-if="tab.icon === 'chat'"
          stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
          d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
        />
        <path
          v-else-if="tab.icon === 'dashboard'"
          stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
          d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"
        />
        <path
          v-else-if="tab.icon === 'ingestion'"
          stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
          d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
        />
        <path
          v-else-if="tab.icon === 'evaluation'"
          stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
          d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"
        />
        <path
          v-else-if="tab.icon === 'settings'"
          stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
          d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
        />
        <path
          v-else-if="tab.icon === 'settings'"
          stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
          d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
        />
      </svg>
      <span
        v-if="!collapsed"
        class="ml-3 text-sm whitespace-nowrap overflow-hidden"
      >
        {{ tab.label }}
      </span>
    </button>

    <div class="mt-auto pb-4 flex justify-center">
      <button
        @click="collapsed = !collapsed"
        class="w-7 h-7 rounded-md flex items-center justify-center transition-colors"
        style="border: 1px solid var(--border); color: var(--text-4)"
        title="展开/收起侧栏"
      >
        <svg
          class="w-3.5 h-3.5 transition-transform duration-300"
          :class="collapsed ? 'rotate-180' : ''"
          fill="none" stroke="currentColor" viewBox="0 0 24 24"
        >
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
        </svg>
      </button>
    </div>
  </aside>
</template>
