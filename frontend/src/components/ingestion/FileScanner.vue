<script setup lang="ts">
import { ref, watch } from 'vue'
import { NButton, NCheckbox } from 'naive-ui'
import type { ScanResult } from '@/api/types/ingestion'

const props = defineProps<{
  scanResult: ScanResult | null
}>()

const emit = defineEmits<{
  confirm: [exts: string[]]
}>()

const selectedExts = ref<string[]>([])

// 扫描结果变化时，全选所有扩展名
watch(() => props.scanResult, (result) => {
  if (result?.extensions) {
    selectedExts.value = result.extensions.map(e => e.ext)
  }
}, { immediate: true })

function toggleExt(ext: string) {
  const idx = selectedExts.value.indexOf(ext)
  if (idx >= 0) {
    selectedExts.value.splice(idx, 1)
  } else {
    selectedExts.value.push(ext)
  }
}

function handleConfirm() {
  if (selectedExts.value.length === 0) {
    window.alert('请至少勾选一种文件类型')
    return
  }
  emit('confirm', [...selectedExts.value])
}
</script>

<template>
  <div class="flex flex-col h-full">
    <h3 class="text-base font-semibold mb-2" style="color: var(--text-1)">选择要处理的文件类型</h3>
    <p class="text-sm mb-6" style="color: var(--text-3)">扫描到以下包含内容的文件格式，请勾选需要入库的格式：</p>

    <div class="flex flex-wrap gap-3 mb-6">
      <label
        v-for="ext in scanResult?.extensions || []"
        :key="ext.ext"
        class="flex items-center gap-2 p-2.5 rounded-lg cursor-pointer transition-colors bg-white shadow-sm"
        style="border: 1px solid var(--border)"
      >
        <input
          type="checkbox"
          :checked="selectedExts.includes(ext.ext)"
          @change="toggleExt(ext.ext)"
          class="w-4 h-4 rounded"
          style="accent-color: var(--c-primary)"
        />
        <span class="text-sm font-medium" style="color: var(--text-2)">
          {{ ext.ext }} <span class="text-xs font-normal" style="color: var(--text-4)">({{ ext.count }} 文件)</span>
        </span>
      </label>
    </div>

    <div class="mt-auto pt-4 flex justify-end" style="border-top: 1px solid var(--border-subtle)">
      <NButton type="primary" @click="handleConfirm">
        确认并开始入库 →
      </NButton>
    </div>
  </div>
</template>
