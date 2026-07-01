/** HTML 转义 */
export function escHtml(str: string): string {
  if (!str) return ''
  const d = document.createElement('div')
  d.textContent = String(str)
  return d.innerHTML
}

/** 复制到剪贴板 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}
