<template>
  <div>
    <div class="page-head">
      <div>
        <h1>仪表盘</h1>
        <p>在线统计按 {{ 5 }} 分钟内的心跳计算，封禁账号单独计数</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="stat-grid">
      <div class="stat">
        <div class="label"><el-icon :size="15"><User /></el-icon>总用户</div>
        <div class="value">{{ num(data.totalUsers) }}</div>
        <div class="hint">今日新增 <strong>{{ num(data.todayNew) }}</strong> · 7 日 {{ num(data.weekNew) }}</div>
      </div>
      <div class="stat">
        <div class="label"><el-icon :size="15"><Connection /></el-icon>在线用户</div>
        <div class="value">{{ num(data.onlineUsers) }}</div>
        <div class="hint">手机 {{ num(data.onlinePhones) }} · 电视 {{ num(data.onlineTvs) }}</div>
      </div>
      <div class="stat">
        <div class="label"><el-icon :size="15"><CircleClose /></el-icon>封禁中</div>
        <div class="value">{{ num(data.bannedUsers) }}</div>
        <div class="hint">含网段风控自动封禁</div>
      </div>
      <div class="stat">
        <div class="label"><el-icon :size="15"><Link /></el-icon>在用片源</div>
        <div class="value">{{ num(data.sourceCount) }}</div>
        <div class="hint">已发布版本 {{ num(data.publishedVersions) }} 个</div>
      </div>
    </div>

    <div class="charts">
      <div class="card">
        <div class="card-title">
          <span>近 7 日注册</span>
          <span class="sub">按自然日统计</span>
        </div>
        <div ref="trendEl" class="chart"></div>
      </div>
      <div class="card">
        <div class="card-title">
          <span>在线平台分布</span>
          <span class="sub">按账号去重</span>
        </div>
        <div ref="platformEl" class="chart"></div>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-title">
        <span>最近自动封禁</span>
        <span class="sub">来自网段风控</span>
      </div>
      <el-table :data="data.recentBans || []" size="default" empty-text="暂无记录">
        <el-table-column prop="username" label="账号" min-width="120" />
        <el-table-column prop="reason" label="原因" min-width="160" />
        <el-table-column label="触发网段" min-width="260">
          <template #default="{ row }">
            <span class="mono">{{ row.segments }}</span>
          </template>
        </el-table-column>
        <el-table-column label="解封时间" width="170">
          <template #default="{ row }">{{ row.banUntil || '—' }}</template>
        </el-table-column>
        <el-table-column label="触发时间" width="170">
          <template #default="{ row }">{{ row.createdAt || '—' }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref, onBeforeUnmount } from 'vue'
// 按需引入：整包 echarts 是 1MB，这里只用到折线 + 环图，
// 拆成 core + 两个图表 + 四个组件后压缩体积不到四分之一
import * as echarts from 'echarts/core'
import { LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, TitleComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { Refresh } from '@element-plus/icons-vue'
import { api } from '../api'

echarts.use([
  LineChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  CanvasRenderer
])

const data = reactive({})
const loading = ref(false)
const trendEl = ref(null)
const platformEl = ref(null)
let trendChart = null
let platformChart = null

function num(v) {
  return v === undefined || v === null ? '—' : v
}

async function load() {
  loading.value = true
  try {
    const res = await api.overview()
    Object.assign(data, res)
    await nextTick()
    renderTrend()
    renderPlatform()
  } finally {
    loading.value = false
  }
}

function renderTrend() {
  if (!trendEl.value) return
  trendChart = trendChart || echarts.init(trendEl.value)
  const trend = data.trend || []
  trendChart.setOption({
    grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: trend.map((t) => t.date),
      axisLine: { lineStyle: { color: '#e8eaee' } },
      axisTick: { show: false },
      axisLabel: { color: '#7b8296', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f0f2f5' } },
      axisLabel: { color: '#7b8296', fontSize: 11 }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: trend.map((t) => t.count),
        itemStyle: { color: '#4f5bd5' },
        lineStyle: { width: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(79,91,213,0.18)' },
            { offset: 1, color: 'rgba(79,91,213,0)' }
          ])
        }
      }
    ]
  })
}

function renderPlatform() {
  if (!platformEl.value) return
  platformChart = platformChart || echarts.init(platformEl.value)
  const list = data.platformDist || []
  const nameOf = (p) => (p === 'phone' ? '手机' : p === 'tv' ? '电视' : p)
  if (!list.length) {
    platformChart.setOption({
      title: {
        text: '暂无在线设备',
        left: 'center',
        top: '38%',
        textStyle: { color: '#b4b9c6', fontSize: 12, fontWeight: 'normal' }
      },
      series: []
    }, true)
    return
  }
  platformChart.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, icon: 'circle', textStyle: { color: '#5a6173', fontSize: 12 } },
    series: [
      {
        type: 'pie',
        radius: ['52%', '72%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        data: list.map((p) => ({
          name: nameOf(p.platform),
          value: p.count,
          itemStyle: { color: p.platform === 'tv' ? '#1d9e75' : '#4f5bd5' }
        }))
      }
    ]
  })
}

function onResize() {
  trendChart?.resize()
  platformChart?.resize()
}

onMounted(() => {
  load()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  trendChart?.dispose()
  platformChart?.dispose()
})
</script>

<style scoped>
.charts {
  display: grid;
  grid-template-columns: 1.6fr 1fr;
  gap: 16px;
  margin-top: 16px;
}

@media (max-width: 1000px) {
  .charts {
    grid-template-columns: 1fr;
  }
}

.chart {
  height: 240px;
  width: 100%;
}
</style>
