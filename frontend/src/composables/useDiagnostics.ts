import { ref } from 'vue'
import { TOOL_NAMES, TOOL_ICONS } from '@/utils/constants'
import { copyToClipboard } from '@/utils/dom'
import type { ChatMessage } from '@/stores/chat'

// 模块级共享状态 — 所有调用者共享同一份
const selectedMessageId = ref<string | null>(null)
const diagExpanded = ref(true)
const userClosedDiag = ref(false)

export function useDiagnostics() {
  function selectMessage(id: string | null) {
    selectedMessageId.value = id
    if (!userClosedDiag.value && id) {
      diagExpanded.value = true
    }
  }

  function toggleDiag() {
    diagExpanded.value = !diagExpanded.value
    if (!diagExpanded.value) {
      userClosedDiag.value = true
    }
  }

  function expandDiag() {
    diagExpanded.value = true
  }

  function collapseDiag() {
    diagExpanded.value = false
    userClosedDiag.value = true
  }

  function getConclusions(msg: ChatMessage): string[] {
    const conclusions: string[] = []
    if (msg.status === 'streaming') {
      conclusions.push('检索与生成进行中...')
    } else {
      if (msg.sources.length >= 3) conclusions.push('本轮包含多个引用来源，证据较充分')
      else if (msg.sources.length > 0) conclusions.push('本轮引用来源较少，建议核实')
      else conclusions.push('本轮未提供引用来源，建议谨慎采信')

      if (msg.toolMetadata.length > 0) {
        conclusions.push(`本轮触发了 ${msg.toolMetadata.length} 次工具调用，回答结合了外部数据`)
      } else {
        conclusions.push('本轮未触发工具调用')
      }

      if (msg.status === 'error') conclusions.push('本轮回答异常终止')
    }
    return conclusions
  }

  return {
    selectedMessageId,
    diagExpanded,
    selectMessage,
    toggleDiag,
    expandDiag,
    collapseDiag,
    getConclusions,
    copyText: copyToClipboard,
  }
}
