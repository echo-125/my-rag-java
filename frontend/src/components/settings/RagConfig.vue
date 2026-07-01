<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import { NInput, NSwitch, NSelect, NButton, NSpin } from 'naive-ui'

const store = useSettingsStore()
const saving = ref(false)

// 本地编辑状态：key -> value
const localValues = ref<Record<string, string>>({})

// 当 reranking 模型为空时，禁用 reranking 相关配置
const hasRerankModels = computed(() => store.rerankingConfigs.length > 0)

// reranking 模型下拉选项
const rerankModelOptions = computed(() =>
  store.rerankingConfigs.map(rc => ({
    label: `${rc.name} (${rc.modelName})`,
    value: rc.modelName,
  }))
)

function isDisabled(key: string): boolean {
  const rerankKeys = ['reranking_model', 'reranking_top_n', 'reranking_pool_size']
  if (rerankKeys.includes(key) && !hasRerankModels.value) return true
  if (key === 'enable_reranking' && !hasRerankModels.value) return true
  return false
}

function initLocalValues() {
  const values: Record<string, string> = {}
  for (const c of store.ragConfigs) {
    values[c.key] = c.value
  }
  localValues.value = values
}

async function handleSave() {
  saving.value = true
  try {
    await store.saveRagConfigs(localValues.value)
    window.alert('保存成功')
  } catch (e: any) {
    window.alert('保存失败: ' + e.message)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  // 等 store 加载完成后初始化本地值
  const unwatch = store.$subscribe(() => {
    if (store.ragConfigs.length > 0) {
      initLocalValues()
      unwatch()
    }
  })
})
</script>

<template>
  <div class="rounded-xl p-5 shadow-sm" style="background: var(--bg-card); border: 1px solid var(--border)">
    <div class="flex items-center justify-between mb-5 pb-3" style="border-bottom: 1px solid var(--border-subtle)">
      <h2 class="text-sm font-bold flex items-center gap-2" style="color: var(--text-1)">
        <div class="w-1.5 h-4 rounded-full" style="background: var(--c-primary)"></div>
        RAG 策略与切片配置
      </h2>
      <NButton size="small" type="primary" :loading="saving" @click="handleSave">
        保存策略
      </NButton>
    </div>

    <NSpin v-if="store.loading" class="flex justify-center py-8" />

    <div v-else-if="store.ragConfigs.length === 0" class="text-sm py-4 text-center" style="color: var(--text-4)">
      暂无配置项
    </div>

    <div v-else class="space-y-0">
      <div
        v-for="config in store.ragConfigs"
        :key="config.key"
        class="py-3"
        style="border-bottom: 1px solid var(--border-subtle)"
      >
        <!-- textarea 类型 -->
        <template v-if="config.type === 'textarea'">
          <div class="text-sm font-medium mb-0.5" style="color: var(--text-2)">{{ config.key }}</div>
          <div class="text-xs mb-1" style="color: var(--text-3)">{{ config.description }}</div>
          <NInput
            v-model:value="localValues[config.key]"
            type="textarea"
            :rows="4"
            :disabled="isDisabled(config.key)"
            placeholder=""
            style="font-family: var(--font-mono)"
          />
        </template>

        <!-- boolean 类型 -->
        <template v-else-if="config.type === 'boolean'">
          <div class="flex items-center justify-between">
            <div class="flex-1 pr-4">
              <div class="text-sm font-medium" style="color: var(--text-2)">{{ config.key }}</div>
              <div class="text-xs" style="color: var(--text-3)">{{ config.description }}</div>
            </div>
            <NSelect
              v-model:value="localValues[config.key]"
              :disabled="isDisabled(config.key)"
              :options="[
                { label: '开启', value: 'true' },
                { label: '关闭', value: 'false' },
              ]"
              style="width: 120px"
              size="small"
            />
          </div>
        </template>

        <!-- reranking_model 特殊处理：下拉框 -->
        <template v-else-if="config.key === 'reranking_model'">
          <div class="flex items-center justify-between">
            <div class="flex-1 pr-4">
              <div class="text-sm font-medium" style="color: var(--text-2)">{{ config.key }}</div>
              <div class="text-xs" style="color: var(--text-3)">{{ config.description }}</div>
            </div>
            <NSelect
              v-model:value="localValues[config.key]"
              :disabled="isDisabled(config.key)"
              :options="[{ label: '-- 请选择 --', value: '' }, ...rerankModelOptions]"
              style="width: 200px"
              size="small"
            />
          </div>
        </template>

        <!-- number 类型 -->
        <template v-else-if="config.type === 'number'">
          <div class="flex items-center justify-between">
            <div class="flex-1 pr-4">
              <div class="text-sm font-medium" style="color: var(--text-2)">{{ config.key }}</div>
              <div class="text-xs" style="color: var(--text-3)">{{ config.description }}</div>
            </div>
            <NInput
              v-model:value="localValues[config.key]"
              type="text"
              :disabled="isDisabled(config.key)"
              style="width: 160px"
              size="small"
            />
          </div>
        </template>

        <!-- text 类型（默认） -->
        <template v-else>
          <div class="flex items-center justify-between">
            <div class="flex-1 pr-4">
              <div class="text-sm font-medium" style="color: var(--text-2)">{{ config.key }}</div>
              <div class="text-xs" style="color: var(--text-3)">{{ config.description }}</div>
            </div>
            <NInput
              v-model:value="localValues[config.key]"
              :disabled="isDisabled(config.key)"
              style="width: 200px"
              size="small"
            />
          </div>
        </template>
      </div>
    </div>
  </div>
</template>
