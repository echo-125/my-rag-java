<script setup lang="ts">
import type { StreamMessage } from '@/composables/useChatStream'
import { formatDuration } from '@/utils/format'
import { useAppStore } from '@/stores/app'

const props = defineProps<{
  message: StreamMessage | null
}>()

const appStore = useAppStore()
</script>

<template>
  <Transition name="slide-right">
    <aside
      v-if="appStore.diagPanelOpen && message"
      class="w-80 shrink-0 border-l border-default bg-elevated overflow-y-auto"
    >
      <div class="sticky top-0 flex items-center justify-between border-b border-default bg-elevated px-3 py-2">
        <span class="text-xs font-medium text-muted uppercase tracking-wider">诊断面板</span>
        <UButton
          icon="lucide:x"
          size="xs"
          color="neutral"
          variant="ghost"
          @click="appStore.toggleDiag()"
        />
      </div>
      <div class="p-3 space-y-4 text-xs">
        <!-- 基本信息 -->
        <div class="space-y-2">
          <h4 class="text-[10px] font-medium text-muted uppercase tracking-wider">消息 ID</h4>
          <p class="font-mono text-default break-all">{{ message.id }}</p>
        </div>

        <!-- 内容统计 -->
        <div class="space-y-2">
          <h4 class="text-[10px] font-medium text-muted uppercase tracking-wider">内容统计</h4>
          <div class="grid grid-cols-2 gap-2">
            <div class="rounded-lg bg-default p-2">
              <div class="text-muted">字符数</div>
              <div class="font-mono text-default mt-0.5">{{ message.content?.length ?? 0 }}</div>
            </div>
            <div class="rounded-lg bg-default p-2">
              <div class="text-muted">引用数</div>
              <div class="font-mono text-default mt-0.5">{{ message.sources?.length ?? 0 }}</div>
            </div>
          </div>
        </div>

        <!-- 工具流水 -->
        <div v-if="message.toolMetadata && message.toolMetadata.length > 0" class="space-y-2">
          <h4 class="text-[10px] font-medium text-muted uppercase tracking-wider">工具调用</h4>
          <div
            v-for="(tool, i) in message.toolMetadata"
            :key="i"
            class="rounded-lg bg-default p-2 space-y-1"
          >
            <div class="flex items-center justify-between">
              <span class="font-mono text-primary">{{ tool.toolName }}</span>
              <span class="text-muted">{{ formatDuration(tool.durationMs) }}</span>
            </div>
            <pre v-if="tool.args" class="text-[10px] text-muted overflow-x-auto whitespace-pre-wrap break-all">{{ JSON.stringify(tool.args, null, 2) }}</pre>
          </div>
        </div>

        <!-- 引用详情 -->
        <div v-if="message.sources && message.sources.length > 0" class="space-y-2">
          <h4 class="text-[10px] font-medium text-muted uppercase tracking-wider">引用来源</h4>
          <div
            v-for="(src, i) in message.sources"
            :key="i"
            class="rounded-lg bg-default p-2 space-y-1"
          >
            <div class="flex items-center justify-between">
              <span class="font-mono text-default truncate">{{ src.fileName }}</span>
              <span class="text-primary font-mono shrink-0 ml-2">{{ (src.score * 100).toFixed(1) }}%</span>
            </div>
            <div class="text-[10px] text-muted">
              {{ src.projectName }} · chunk #{{ src.chunkIndex }}
            </div>
          </div>
        </div>

        <!-- 快速操作 -->
        <div class="space-y-2">
          <h4 class="text-[10px] font-medium text-muted uppercase tracking-wider">快速操作</h4>
          <div class="flex gap-2">
            <UButton size="xs" color="neutral" variant="outline" icon="lucide:copy">
              复制内容
            </UButton>
            <UButton size="xs" color="neutral" variant="outline" icon="lucide:refresh-cw">
              重新生成
            </UButton>
          </div>
        </div>
      </div>
    </aside>
  </Transition>
</template>

<style scoped>
.slide-right-enter-active, .slide-right-leave-active {
  transition: all 0.3s ease;
}
.slide-right-enter-from, .slide-right-leave-to {
  transform: translateX(100%);
  opacity: 0;
}
</style>
