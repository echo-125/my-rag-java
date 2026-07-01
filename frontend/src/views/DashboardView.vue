<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getStats, getFeedbackStats } from '@/api/dashboard'
import type { DashboardStats, FeedbackStats } from '@/api/types/dashboard'
import StatCard from '@/components/dashboard/StatCard.vue'
import LangChart from '@/components/dashboard/LangChart.vue'
import QaHistory from '@/components/dashboard/QaHistory.vue'
import EvalBrief from '@/components/dashboard/EvalBrief.vue'

const stats = ref<DashboardStats | null>(null)
const feedback = ref<FeedbackStats | null>(null)

onMounted(async () => {
  try {
    const [s, f] = await Promise.all([
      getStats().catch(() => null),
      getFeedbackStats().catch(() => null),
    ])
    stats.value = s
    feedback.value = f
  } catch {}
})
</script>

<template>
  <div class="flex-1 overflow-y-auto p-6" style="background: var(--bg-page)">
    <div class="max-w-[1600px] mx-auto h-full flex flex-col gap-4">

      <!-- 顶部：核心指标 -->
      <div class="grid grid-cols-2 lg:grid-cols-4 gap-4 flex-shrink-0">
        <StatCard title="总文档块 (Chunks)" :value="stats?.totalChunks ?? '—'" icon="📦" color="#2563eb" />
        <StatCard title="已索引文件" :value="stats?.fileCount ?? '—'" icon="📄" color="#16a34a" />
        <StatCard title="知识库项目数" :value="stats?.projectCount ?? '—'" icon="📁" color="#9333ea" />
        <StatCard title="最后更新时间" :value="stats?.lastProcessTime ?? '—'" icon="🕐" color="#d97706" />
      </div>

      <!-- 主体区域 -->
      <div class="grid grid-cols-1 lg:grid-cols-3 gap-4 flex-1 min-h-[500px]">

        <!-- 左侧：图表与架构 -->
        <div class="col-span-2 flex flex-col gap-4">
          <!-- 语言分布图 -->
          <div class="rounded-xl p-5 shadow-sm flex-1 flex flex-col" style="background: var(--bg-card); border: 1px solid var(--border)">
            <h2 class="text-sm font-semibold mb-4 flex items-center gap-2" style="color: var(--text-1)">
              <div class="w-1.5 h-4 rounded-full" style="background: var(--c-primary)"></div>
              各项目代码量统计
            </h2>
            <div class="flex-1 min-h-[250px]">
              <LangChart />
            </div>
          </div>

          <!-- 反馈统计 -->
          <div class="rounded-xl shadow-sm p-4" style="background: var(--bg-card); border: 1px solid var(--border)">
            <h2 class="text-sm font-semibold mb-3 flex items-center gap-2" style="color: var(--text-1)">
              <div class="w-1.5 h-4 rounded-full" style="background: #16a34a"></div>
              用户反馈
            </h2>
            <div v-if="feedback && feedback.total > 0" class="flex items-center justify-between">
              <div class="text-center">
                <div class="text-xl font-bold" style="color: #16a34a">{{ feedback.positive }}</div>
                <div class="text-xs" style="color: var(--text-3)">👍</div>
              </div>
              <div class="text-center">
                <div class="text-2xl font-bold" style="color: var(--c-primary)">{{ feedback.positiveRate }}%</div>
                <div class="text-xs" style="color: var(--text-3)">满意率</div>
              </div>
              <div class="text-center">
                <div class="text-xl font-bold" style="color: #dc2626">{{ feedback.negative }}</div>
                <div class="text-xs" style="color: var(--text-3)">👎</div>
              </div>
            </div>
            <div v-else class="text-sm text-center py-4" style="color: var(--text-4)">暂无反馈</div>
          </div>
        </div>

        <!-- 右侧：问答记录 + 评估报告 -->
        <div class="col-span-1 flex flex-col gap-4 h-full overflow-hidden">
          <!-- 最近问答 -->
          <div class="rounded-xl shadow-sm flex flex-col flex-1 overflow-hidden" style="background: var(--bg-card); border: 1px solid var(--border)">
            <div class="p-4 border-b" style="border-color: var(--border-subtle); background: var(--bg-elevated)">
              <h2 class="text-sm font-semibold flex items-center gap-2" style="color: var(--text-1)">
                <div class="w-1.5 h-4 rounded-full" style="background: var(--c-primary)"></div>
                最近问答记录
              </h2>
            </div>
            <QaHistory />
          </div>

          <!-- 评估报告 -->
          <div class="rounded-xl shadow-sm" style="background: var(--bg-card); border: 1px solid var(--border)">
            <div class="p-4 border-b" style="border-color: var(--border-subtle)">
              <h2 class="text-sm font-semibold flex items-center gap-2" style="color: var(--text-1)">
                <div class="w-1.5 h-4 rounded-full" style="background: #2563eb"></div>
                评估报告
              </h2>
            </div>
            <EvalBrief />
          </div>
        </div>

      </div>
    </div>
  </div>
</template>
