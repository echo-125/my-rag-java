/** 格式化数字（添加千分位） */
export function formatNumber(n: number): string {
  return n.toLocaleString('zh-CN')
}

/** 格式化时间（短格式） */
export function formatTime(date: string | Date): string {
  const d = typeof date === 'string' ? new Date(date) : date
  return d.toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** 格式化持续时间（毫秒 → 秒） */
export function formatDuration(ms: number): string {
  return (ms / 1000).toFixed(1) + 's'
}
