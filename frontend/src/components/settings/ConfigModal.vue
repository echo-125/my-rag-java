<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { NModal, NInput, NSelect, NButton, NSpace } from 'naive-ui'

interface Field {
  key: string
  label: string
  required?: boolean
  type?: 'text' | 'password' | 'number' | 'select' | 'textarea'
  placeholder?: string
  options?: Array<{ label: string; value: string }>
}

const props = defineProps<{
  visible: boolean
  title: string
  fields: Field[]
  initialValues?: Record<string, string>
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  save: [values: Record<string, string>]
}>()

const formValues = ref<Record<string, string>>({})

// 初始化表单值
watch(() => props.visible, (v) => {
  if (v) {
    const defaults: Record<string, string> = {}
    for (const f of props.fields) {
      defaults[f.key] = props.initialValues?.[f.key] || ''
    }
    formValues.value = defaults
  }
})

const isValid = computed(() => {
  return props.fields
    .filter(f => f.required)
    .every(f => formValues.value[f.key]?.trim())
})

function handleSave() {
  if (!isValid.value) return
  emit('save', { ...formValues.value })
}

function handleClose() {
  emit('update:visible', false)
}
</script>

<template>
  <NModal
    :show="visible"
    @update:show="(v) => emit('update:visible', v)"
    :mask-closable="true"
  >
    <div class="rounded-xl shadow-xl w-full max-w-lg max-h-[90vh] overflow-y-auto" style="background: var(--bg-card)">
      <div class="p-5 flex items-center justify-between" style="border-bottom: 1px solid var(--border)">
        <h3 class="text-base font-semibold" style="color: var(--text-1)">{{ title }}</h3>
        <button @click="handleClose" class="text-lg" style="color: var(--text-4)">&times;</button>
      </div>

      <div class="p-5 space-y-4">
        <div v-for="field in fields" :key="field.key">
          <label class="block text-sm font-medium mb-1" style="color: var(--text-2)">
            {{ field.label }}
            <span v-if="field.required" style="color: var(--status-error)">*</span>
          </label>

          <!-- select 类型 -->
          <NSelect
            v-if="field.type === 'select'"
            v-model:value="formValues[field.key]"
            :options="field.options || []"
            size="medium"
          />

          <!-- textarea 类型 -->
          <NInput
            v-else-if="field.type === 'textarea'"
            v-model:value="formValues[field.key]"
            type="textarea"
            :rows="4"
            :placeholder="field.placeholder"
            style="font-family: var(--font-mono)"
          />

          <!-- 其他类型（text/password/number） -->
          <NInput
            v-else
            v-model:value="formValues[field.key]"
            :type="field.type === 'number' ? 'text' : (field.type || 'text')"
            :placeholder="field.placeholder"
          />
        </div>
      </div>

      <div class="p-5 flex justify-end gap-2" style="border-top: 1px solid var(--border)">
        <NButton @click="handleClose">取消</NButton>
        <NButton type="primary" :disabled="!isValid" @click="handleSave">保存</NButton>
      </div>
    </div>
  </NModal>
</template>
