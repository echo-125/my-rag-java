import { useIntervalFn } from '@vueuse/core'

export function usePolling(
  fn: () => Promise<void> | void,
  interval = 2000,
) {
  const isPolling = ref(false)
  const { pause, resume, isActive } = useIntervalFn(fn, interval, { immediateCallback: false })

  function start() {
    isPolling.value = true
    resume()
  }

  function stop() {
    isPolling.value = false
    pause()
  }

  return { isPolling, start, stop, isActive }
}
