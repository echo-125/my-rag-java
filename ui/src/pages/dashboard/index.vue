<script setup lang="ts">
import { api } from '@/utils/api'
import { formatNumber, formatRelative } from '@/utils/format'
import { useMermaid } from '@/composables/useMermaid'
import StatCard from '@/components/StatCard.vue'
import type { DashboardStats, LanguageStat, RecentQA, EvaluationReport, FeedbackStats } from '@/types/dashboard'

const stats = ref<DashboardStats | null>(null)
const languages = ref<LanguageStat[]>([])
const recentQA = ref<RecentQA[]>([])
const evalReport = ref<EvaluationReport | null>(null)
const feedbackStats = ref<FeedbackStats | null>(null)
const loading = ref(true)

async function loadData() {
  loading.value = true
  try {
    const [s, l, q, e, f] = await Promise.allSettled([
      api<DashboardStats>('/dashboard/stats'),
      api<LanguageStat[]>('/dashboard/language-stats'),
      api<RecentQA[]>('/dashboard/recent-qa'),
      api<EvaluationReport>('/evaluation/report'),
      api<FeedbackStats>('/feedback/stats'),
    ])
    if (s.status === 'fulfilled') stats.value = s.value
    if (l.status === 'fulfilled') languages.value = l.value
    if (q.status === 'fulfilled') recentQA.value = q.value
    if (e.status === 'fulfilled') evalReport.value = e.value
    if (f.status === 'fulfilled') feedbackStats.value = f.value
  } finally {
    loading.value = false
  }
}

const chartOptions = computed(() => ({
  chart: { type: 'bar', background: 'transparent', toolbar: { show: false } },
  theme: { mode: 'dark' as const },
  colors: ['#10a37f'],
  xaxis: {
    categories: languages.value.map(l => l.language),
    labels: { style: { colors: '#94a3b8', fontSize: '11px' } },
  },
  yaxis: {
    labels: { style: { colors: '#94a3b8', fontSize: '11px' } },
  },
  grid: { borderColor: '#1e293b' },
}))

const chartSeries = computed(() => [{
  name: '文件数',
  data: languages.value.map(l => l.count),
}])

const positiveRate = computed(() => {
  if (!feedbackStats.value || feedbackStats.value.total === 0) return 0
  return ((feedbackStats.value.positive / feedbackStats.value.total) * 100).toFixed(1)
})

const { render: renderMermaid } = useMermaid()
const archDiagramRef = ref<HTMLElement | null>(null)

const mermaidCode = `graph TD
    A[用户输入] --> B{查询改写}
    B --> C[混合检索 BM25+向量]
    C --> D[Reranking 精排]
    D --> E[LLM 生成回答]
    E --> F[流式输出]
    C --> G[(PgVector)]
    G --> H[文档入库]
    H --> I[切分 + 向量化]`

async function renderArchDiagram() {
  if (!archDiagramRef.value) return
  const svg = await renderMermaid(mermaidCode, 'arch')
  archDiagramRef.value.innerHTML = svg
}

onMounted(async () => {
  await loadData()
  await renderArchDiagram()
})
</script>

<template>
  <div class="flex-1 overflow-y-auto p-6 space-y-6">
    <!-- 统计卡片 -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4">
      <StatCard
        label="知识块"
        :value="stats ? formatNumber(stats.totalChunks) : '—'"
        icon="lucide:database"
      />
      <StatCard
        label="文件数"
        :value="stats ? formatNumber(stats.totalFiles) : '—'"
        icon="lucide:file"
      />
      <StatCard
        label="项目数"
        :value="stats?.totalProjects ?? '—'"
        icon="lucide:folder"
      />
      <StatCard
        label="最近入库"
        :value="stats?.lastIngestionTime ? formatRelative(stats.lastIngestionTime) : '无'"
        icon="lucide:clock"
      />
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
      <!-- 语言统计图表 -->
      <div class="rounded-xl border border-default bg-elevated p-4">
        <h3 class="text-sm font-medium text-default mb-3">代码语言分布</h3>
        <div v-if="languages.length > 0" class="h-64">
          <VueApexCharts
            type="bar"
            height="100%"
            :options="chartOptions"
            :series="chartSeries"
          />
        </div>
        <div v-else class="flex items-center justify-center h-64 text-sm text-muted">
          暂无数据
        </div>
      </div>

      <!-- 评估报告摘要 -->
      <div class="rounded-xl border border-default bg-elevated p-4">
        <h3 class="text-sm font-medium text-default mb-3">评估报告摘要</h3>
        <div v-if="evalReport" class="grid grid-cols-3 gap-3">
          <div class="rounded-lg bg-default p-3 text-center">
            <div class="text-lg font-mono font-bold text-primary">{{ (evalReport.avgPrecision * 100).toFixed(1) }}%</div>
            <div class="text-[10px] text-muted mt-1">Precision@K</div>
          </div>
          <div class="rounded-lg bg-default p-3 text-center">
            <div class="text-lg font-mono font-bold text-primary">{{ (evalReport.avgHitRate * 100).toFixed(1) }}%</div>
            <div class="text-[10px] text-muted mt-1">Hit Rate</div>
          </div>
          <div class="rounded-lg bg-default p-3 text-center">
            <div class="text-lg font-mono font-bold text-primary">{{ (evalReport.avgMrr * 100).toFixed(1) }}%</div>
            <div class="text-[10px] text-muted mt-1">MRR</div>
          </div>
          <div class="rounded-lg bg-default p-3 text-center">
            <div class="text-lg font-mono font-bold text-default">{{ evalReport.avgLatencyMs.toFixed(0) }}ms</div>
            <div class="text-[10px] text-muted mt-1">平均延迟</div>
          </div>
          <div class="rounded-lg bg-default p-3 text-center">
            <div class="text-lg font-mono font-bold text-default">{{ evalReport.totalBatches }}</div>
            <div class="text-[10px] text-muted mt-1">评估批次</div>
          </div>
        </div>
        <div v-else class="flex items-center justify-center h-32 text-sm text-muted">
          暂无评估数据
        </div>
      </div>

      <!-- 反馈统计 -->
      <div class="rounded-xl border border-default bg-elevated p-4">
        <h3 class="text-sm font-medium text-default mb-3">用户反馈统计</h3>
        <div v-if="feedbackStats && feedbackStats.total > 0" class="space-y-3">
          <div class="flex items-center justify-between">
            <span class="text-sm text-muted">满意率</span>
            <span class="text-sm font-mono font-bold text-primary">{{ positiveRate }}%</span>
          </div>
          <div class="h-2 rounded-full bg-default overflow-hidden">
            <div class="h-full bg-primary rounded-full" :style="{ width: `${positiveRate}%` }" />
          </div>
          <div class="flex justify-between text-xs text-muted">
            <span>👍 {{ feedbackStats.positive }}</span>
            <span>👎 {{ feedbackStats.negative }}</span>
            <span class="font-mono">共 {{ feedbackStats.total }} 条</span>
          </div>
        </div>
        <div v-else class="flex items-center justify-center h-32 text-sm text-muted">
          暂无反馈数据
        </div>
      </div>

      <!-- 最近问答 -->
      <div class="rounded-xl border border-default bg-elevated p-4">
        <h3 class="text-sm font-medium text-default mb-3">最近问答记录</h3>
        <div v-if="recentQA.length > 0" class="space-y-2 max-h-64 overflow-y-auto">
          <div
            v-for="qa in recentQA"
            :key="qa.id"
            class="rounded-lg bg-default p-2.5 space-y-1"
          >
            <p class="text-xs text-default line-clamp-1">Q: {{ qa.question }}</p>
            <p class="text-[11px] text-muted line-clamp-2">A: {{ qa.answer }}</p>
            <div class="flex items-center gap-2 text-[10px] text-muted">
              <span class="font-mono">{{ qa.modelUsed }}</span>
              <span>{{ formatRelative(qa.createdAt) }}</span>
            </div>
          </div>
        </div>
        <div v-else class="flex items-center justify-center h-32 text-sm text-muted">
          暂无问答记录
        </div>
      </div>
    </div>

    <!-- 系统架构图 (Mermaid) -->
    <div class="rounded-xl border border-default bg-elevated p-4">
      <h3 class="text-sm font-medium text-default mb-3">系统架构</h3>
      <div class="flex justify-center py-4">
        <div ref="archDiagramRef" class="mermaid-rendered" />
      </div>
    </div>
  </div>
</template>
