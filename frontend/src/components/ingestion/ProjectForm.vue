<script setup lang="ts">
import { ref } from 'vue'
import { NInput, NButton } from 'naive-ui'
import { createProject } from '@/api/ingestion'

const emit = defineEmits<{
  created: []
}>()

const name = ref('')
const path = ref('')
const loading = ref(false)

async function handleSubmit() {
  if (!name.value.trim()) {
    window.alert('请输入项目名称')
    return
  }
  if (!path.value.trim()) {
    window.alert('请输入绝对路径')
    return
  }

  loading.value = true
  try {
    await createProject({ name: name.value.trim(), path: path.value.trim() })
    name.value = ''
    path.value = ''
    emit('created')
  } catch (e: any) {
    window.alert('添加失败: ' + e.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="p-4 space-y-3" style="border-bottom: 1px solid var(--border)">
    <NInput v-model:value="name" placeholder="项目名称 (例如: core-backend)" size="small" />
    <NInput v-model:value="path" placeholder="绝对路径 (例如: D:\code\project)" size="small" />
    <NButton
      type="primary"
      block
      :loading="loading"
      @click="handleSubmit"
    >
      添加项目
    </NButton>
  </div>
</template>
