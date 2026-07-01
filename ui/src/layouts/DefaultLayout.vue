<script setup lang="ts">
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()

const navItems = [
  { name: 'chat', label: '对话', icon: 'lucide:message-square' },
  { name: 'dashboard', label: '仪表盘', icon: 'lucide:layout-dashboard' },
  { name: 'ingestion', label: '文档入库', icon: 'lucide:database' },
  { name: 'evaluation', label: '评估', icon: 'lucide:bar-chart-3' },
  { name: 'settings', label: '设置', icon: 'lucide:settings' },
]
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-default text-default">
    <!-- Sidebar -->
    <aside
      class="flex flex-col border-r border-default bg-default transition-all duration-300"
      :class="appStore.sidebarCollapsed ? 'w-16' : 'w-56'"
    >
      <!-- Logo -->
      <div class="flex h-12 items-center gap-2 border-b border-default px-3">
        <div class="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-primary text-white text-xs font-bold">
          R
        </div>
        <Transition name="fade">
          <span v-if="!appStore.sidebarCollapsed" class="text-sm font-semibold truncate">
            RAG Lab
          </span>
        </Transition>
      </div>

      <!-- Navigation -->
      <nav class="flex-1 py-2">
        <RouterLink
          v-for="item in navItems"
          :key="item.name"
          :to="{ name: item.name }"
          class="flex items-center gap-3 px-3 py-2 mx-1 rounded-lg text-sm transition-colors"
          :class="[
            $route.name === item.name
              ? 'bg-primary/15 text-primary font-medium'
              : 'text-muted hover:bg-elevated hover:text-default',
          ]"
        >
          <UIcon :name="item.icon" class="h-4 w-4 shrink-0" />
          <span v-if="!appStore.sidebarCollapsed" class="truncate">{{ item.label }}</span>
        </RouterLink>
      </nav>

      <!-- Collapse toggle -->
      <div class="border-t border-default p-2">
        <button
          class="flex w-full items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm text-muted hover:bg-elevated transition-colors"
          @click="appStore.toggleSidebar()"
        >
          <UIcon
            :name="appStore.sidebarCollapsed ? 'lucide:chevron-right' : 'lucide:chevron-left'"
            class="h-4 w-4"
          />
          <span v-if="!appStore.sidebarCollapsed">收起</span>
        </button>
      </div>
    </aside>

    <!-- Main -->
    <main class="flex flex-1 flex-col overflow-hidden">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.fade-enter-active, .fade-leave-active { transition: opacity 0.2s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
