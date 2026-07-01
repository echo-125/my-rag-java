import { ref, onMounted, onBeforeUnmount, watch, type Ref, nextTick } from 'vue'
import * as echarts from 'echarts'

/**
 * ECharts 生命周期管理 composable
 * 封装 init / resize / dispose，自动处理组件卸载和窗口 resize
 */
export function useChart(containerRef: Ref<HTMLElement | null>) {
  let chart: echarts.ECharts | null = null
  const isReady = ref(false)

  function init() {
    if (!containerRef.value || chart) return
    chart = echarts.init(containerRef.value, undefined, { renderer: 'canvas' })
    isReady.value = true
  }

  function setOption(option: echarts.EChartsOption) {
    if (!chart) {
      init()
    }
    chart?.setOption(option)
  }

  function resize() {
    chart?.resize()
  }

  function dispose() {
    if (chart) {
      chart.dispose()
      chart = null
      isReady.value = false
    }
  }

  function getInstance() {
    return chart
  }

  // 窗口 resize 自动适配
  let resizeObserver: ResizeObserver | null = null

  onMounted(() => {
    init()
    if (containerRef.value) {
      resizeObserver = new ResizeObserver(() => resize())
      resizeObserver.observe(containerRef.value)
    }
    window.addEventListener('resize', resize)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', resize)
    resizeObserver?.disconnect()
    dispose()
  })

  return { isReady, init, setOption, resize, dispose, getInstance }
}
