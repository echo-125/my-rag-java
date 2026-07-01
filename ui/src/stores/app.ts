import { ref } from 'vue'
import { defineStore } from 'pinia'
import { useColorMode } from '@vueuse/core'

export const useAppStore = defineStore('app', () => {
  const colorMode = useColorMode({
    attribute: 'class',
    initialValue: 'dark',
    storageKey: 'rag-theme',
  })

  const sidebarCollapsed = ref(false)
  const diagPanelOpen = ref(false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  function toggleDiag() {
    diagPanelOpen.value = !diagPanelOpen.value
  }

  function toggleTheme() {
    colorMode.value = colorMode.value === 'dark' ? 'light' : 'dark'
  }

  return {
    colorMode,
    sidebarCollapsed,
    diagPanelOpen,
    toggleSidebar,
    toggleDiag,
    toggleTheme,
  }
})
