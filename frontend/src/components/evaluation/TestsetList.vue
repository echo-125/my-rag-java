<script setup lang="ts">
import { NButton, NModal, NInput } from 'naive-ui'
import { ref } from 'vue'
import type { Testset } from '@/api/types/evaluation'

const props = defineProps<{
  testsets: Testset[]
  currentId: string | null
}>()

const emit = defineEmits<{
  select: [id: string, name: string]
  delete: [id: string, name: string]
  create: [name: string, description: string]
  export: [id: string]
  import: [file: File]
}>()

const createModalVisible = ref(false)
const newName = ref('')
const newDesc = ref('')
const importInputRef = ref<HTMLInputElement>()

function handleCreate() {
  if (!newName.value.trim()) return
  emit('create', newName.value.trim(), newDesc.value)
  createModalVisible.value = false
  newName.value = ''
  newDesc.value = ''
}

function handleImport() {
  importInputRef.value?.click()
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files?.[0]) {
    emit('import', input.files[0])
    input.value = ''
  }
}
</script>

<template>
  <div class="rounded-xl shadow-sm flex flex-col overflow-hidden" style="background: var(--bg-card); border: 1px solid var(--border)">
    <div class="p-4 flex items-center justify-between" style="border-bottom: 1px solid var(--border-subtle); background: var(--bg-elevated)">
      <h2 class="text-sm font-bold flex items-center gap-2" style="color: var(--text-1)">
        <div class="w-1.5 h-4 rounded-full" style="background: #2563eb"></div>
        测试集
      </h2>
      <div class="flex items-center gap-2">
        <input ref="importInputRef" type="file" accept=".json" class="hidden" @change="onFileChange" />
        <NButton size="tiny" @click="handleImport">导入</NButton>
        <NButton size="tiny" type="primary" @click="createModalVisible = true">+ 新建</NButton>
      </div>
    </div>

    <div class="overflow-y-auto max-h-[200px]">
      <div v-if="testsets.length === 0" class="text-sm text-center py-6" style="color: var(--text-4)">暂无测试集</div>
      <div
        v-for="ts in testsets"
        :key="ts.id"
        class="flex items-center gap-2 px-4 py-3 cursor-pointer transition-colors"
        :style="{ background: ts.id === currentId ? '#eff6ff' : 'transparent' }"
        style="border-bottom: 1px solid var(--border-subtle)"
        @click="emit('select', ts.id, ts.name)"
      >
        <div class="flex-1 min-w-0">
          <div class="text-sm font-medium truncate" style="color: var(--text-1)">{{ ts.name }}</div>
          <div class="text-xs" style="color: var(--text-4)">{{ ts.caseCount }} 条用例</div>
        </div>
        <div class="flex items-center gap-1 flex-shrink-0">
          <button class="text-xs p-1 rounded transition-colors" style="color: var(--text-4)" @click.stop="emit('export', ts.id)" title="导出">↓</button>
          <button class="text-xs p-1 rounded transition-colors" style="color: var(--text-4)" @click.stop="emit('delete', ts.id, ts.name)" title="删除">✕</button>
        </div>
      </div>
    </div>

    <NModal v-model:show="createModalVisible">
      <div class="rounded-xl shadow-xl w-full max-w-md p-5" style="background: var(--bg-card)">
        <h3 class="text-base font-semibold mb-4" style="color: var(--text-1)">新建测试集</h3>
        <div class="space-y-3">
          <NInput v-model:value="newName" placeholder="测试集名称" />
          <NInput v-model:value="newDesc" placeholder="描述（可选）" />
        </div>
        <div class="flex justify-end gap-2 mt-4">
          <NButton @click="createModalVisible = false">取消</NButton>
          <NButton type="primary" :disabled="!newName.trim()" @click="handleCreate">创建</NButton>
        </div>
      </div>
    </NModal>
  </div>
</template>
