import { ref, computed } from 'vue'
import { TOOL_NAMES, TOOL_ICONS } from '@/utils/constants'
import { copyToClipboard } from '@/utils/dom'
import type { ChatMessage } from '@/stores/chat'

export function useDiagnostics() {
  const selectedMessageId = ref<string | null>(null)
  const diagExpanded = ref(true)
  const userClosedDiag = ref(false)

  function selectMessage(id: string | null) {
    selectedMessageId.value = id
    // 自动展开诊断栏（除非用户手动关闭过）
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

  /** 生成诊断结论列表 */
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

  /** 获取工具名称（中文） */
  function getToolName(tool: string): string {
    return TOOL_NAMES[tool] || tool
  }

  /** 获取工具图标 */
  function getToolIcon(tool: string): string {
    return TOOL_ICONS[tool] || '⚙️'
  }

  /** 复制操作 */
  async function copyText(text: string) {
    await copyToClipboard(text)
  }

  return {
    selectedMessageId,
    diagExpanded,
    selectMessage,
    toggleDiag,
    expandDiag,
    collapseDiag,
    getConclusions,
    getToolName,
    getToolIcon,
    copyText,
  }
}
