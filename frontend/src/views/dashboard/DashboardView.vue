<script setup lang="ts">
/**
 * 工作台（M4-S5 实装；原型 prototype/dashboard.html，PRD §10.1）。
 *
 * 【数据面】7 个聚合端点一次拉齐（summary / task-trend / resource-usage /
 * cluster-health / queue-backlog / recent-failures / latest-alerts）。
 * 登录即可见（docs/07 line 555，不挂权限点）；task 维度受 DataScope（O-70）。
 *
 * 【趋势图】自绘 SVG 柱状（不引图表库 —— docs/04 §1.1 的锁栈纪律）：
 * 三条序列（提交/成功/失败）× 7 天，坐标轴只画基线，运维页不追求图表花活。
 */
import { computed, onMounted, ref } from 'vue'
import { dashboardApi } from '@/api/modules/dashboard'
import type {
  ClusterHealthItem,
  DashboardSummary,
  QueueBacklogItem,
  RecentFailure,
  ResourceUsage,
  TaskTrendPoint,
} from '@/api/types/dashboard'
import type { AlertRecord } from '@/api/types/alert'
import { ALERT_LEVEL_LABEL } from '@/api/types/alert'
import ToneChip from '@/components/biz/ToneChip.vue'
import {
  TASK_STATUS_LABEL,
  TASK_STATUS_TONE,
  ALERT_LEVEL_TONE,
  type TaskStatus,
  type AlertLevel,
  type Tone,
} from '@/types/enums'
import { dash, formatDateTime } from '@/utils/format'

const loading = ref(true)
const summary = ref<DashboardSummary | null>(null)
const trend = ref<TaskTrendPoint[]>([])
const resources = ref<ResourceUsage[]>([])
const clusters = ref<ClusterHealthItem[]>([])
const backlogs = ref<QueueBacklogItem[]>([])
const failures = ref<RecentFailure[]>([])
const alerts = ref<AlertRecord[]>([])

onMounted(async () => {
  try {
    // 七个端点彼此独立，任一失败不拖垮整页（Promise.allSettled 逐段兜底）
    const results = await Promise.allSettled([
      dashboardApi.summary(),
      dashboardApi.taskTrend(7),
      dashboardApi.resourceUsage(),
      dashboardApi.clusterHealth(),
      dashboardApi.queueBacklog(),
      dashboardApi.recentFailures(),
      dashboardApi.latestAlerts(),
    ])
    const [s, t, r, c, q, f, a] = results
    if (s.status === 'fulfilled') summary.value = s.value
    if (t.status === 'fulfilled') trend.value = t.value
    if (r.status === 'fulfilled') resources.value = r.value
    if (c.status === 'fulfilled') clusters.value = c.value
    if (q.status === 'fulfilled') backlogs.value = q.value
    if (f.status === 'fulfilled') failures.value = f.value
    if (a.status === 'fulfilled') alerts.value = a.value
  } finally {
    loading.value = false
  }
})

const statusLabel = (s: string): string => TASK_STATUS_LABEL[s as TaskStatus] ?? s
const statusTone = (s: string): Tone => TASK_STATUS_TONE[s as TaskStatus] ?? 'idle'
const levelLabel = (l: string): string => ALERT_LEVEL_LABEL[l] ?? l
const levelTone = (l: string): Tone => ALERT_LEVEL_TONE[l as AlertLevel] ?? 'idle'

const successRateText = computed(() =>
  summary.value?.successRate === null || summary.value?.successRate === undefined
    ? '—'
    : `${(summary.value.successRate * 100).toFixed(1)}%`,
)

/** 成功率 < 80% 染警告色（null 不参与比较） */
const successRateLow = computed(() => {
  const rate = summary.value?.successRate
  return rate !== null && rate !== undefined && rate < 0.8
})

// ── SVG 趋势图（自绘，锁栈纪律）────────────────────────────
const TREND_W = 640
const TREND_H = 160
const trendMax = computed(() => Math.max(1, ...trend.value.map((p) => p.submitted)))
function barX(i: number): number {
  const slot = TREND_W / Math.max(1, trend.value.length)
  return i * slot + slot * 0.18
}
function barW(): number {
  const slot = TREND_W / Math.max(1, trend.value.length)
  return slot * 0.64
}
function barH(v: number): number {
  return (v / trendMax.value) * (TREND_H - 28)
}
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h1>工作台</h1>
        <div class="sub"><span>平台运行概览 · 数据按权限范围过滤（PRD §10.1）</span></div>
      </div>
    </div>

    <!-- 指标卡 -->
    <div class="cards">
      <div class="card">
        <div class="card-label">任务总数</div>
        <div class="card-value mono">{{ summary?.taskTotal ?? '—' }}</div>
        <div class="card-sub">运行中 {{ summary?.runningCount ?? '—' }}</div>
      </div>
      <div class="card">
        <div class="card-label">成功率</div>
        <div class="card-value mono" :style="{ color: successRateLow ? 'var(--warn)' : 'var(--ok)' }">
          {{ successRateText }}
        </div>
        <div class="card-sub">按终态任务计</div>
      </div>
      <div class="card">
        <div class="card-label">队列积压</div>
        <div class="card-value mono">{{ summary?.queueBacklog ?? '—' }}</div>
        <div class="card-sub">等待调度任务数</div>
      </div>
      <div class="card">
        <div class="card-label">节点在线</div>
        <div class="card-value mono">{{ summary?.onlineNodes ?? '—' }} / {{ summary?.totalNodes ?? '—' }}</div>
        <div class="card-sub">执行节点心跳正常</div>
      </div>
      <div class="card">
        <div class="card-label">今日失败</div>
        <div class="card-value mono" :style="{ color: (summary?.failedToday ?? 0) > 0 ? 'var(--fail)' : 'var(--t1)' }">
          {{ summary?.failedToday ?? '—' }}
        </div>
        <div class="card-sub">按业务日期今日</div>
      </div>
      <div class="card">
        <div class="card-label">今日告警</div>
        <div class="card-value mono" :style="{ color: (summary?.alerts ?? 0) > 0 ? 'var(--warn)' : 'var(--t1)' }">
          {{ summary?.alerts ?? '—' }}
        </div>
        <div class="card-sub">今日触发</div>
      </div>
    </div>

    <div class="grid-2">
      <!-- 趋势（自绘 SVG） -->
      <div class="panel">
        <div class="panel-h"><b>近 7 日任务趋势（按提交日归属）</b></div>
        <div v-if="trend.length > 0" class="trend-wrap">
          <svg :viewBox="`0 0 ${TREND_W} ${TREND_H}`" class="trend-svg">
            <line :x1="0" :y1="TREND_H - 20" :x2="TREND_W" :y2="TREND_H - 20" class="axis" />
            <g v-for="(p, i) in trend" :key="p.date">
              <rect :x="barX(i)" :y="TREND_H - 20 - barH(p.success)" :width="barW() / 2" :height="barH(p.success)" class="bar-ok" rx="2" />
              <rect :x="barX(i) + barW() / 2" :y="TREND_H - 20 - barH(p.failed)" :width="barW() / 2" :height="barH(p.failed)" class="bar-fail" rx="2" />
              <text :x="barX(i) + barW() / 2" :y="TREND_H - 6" text-anchor="middle" class="tick">{{ p.date.slice(5) }}</text>
              <text v-if="p.submitted > 0" :x="barX(i) + barW() / 2" :y="TREND_H - 24 - Math.max(barH(p.success), barH(p.failed))" text-anchor="middle" class="val">
                {{ p.submitted }}
              </text>
            </g>
          </svg>
          <div class="legend">
            <span><i class="dot ok" />成功</span>
            <span><i class="dot fail" />失败</span>
          </div>
        </div>
        <div v-else class="empty-hint">暂无趋势数据</div>
      </div>

      <!-- 队列积压 -->
      <div class="panel">
        <div class="panel-h"><b>队列积压</b></div>
        <table class="tbl">
          <thead>
            <tr><th>队列</th><th>等待</th><th>上限水位</th></tr>
          </thead>
          <tbody>
            <tr v-for="q in backlogs" :key="q.queueId">
              <td>{{ q.queueName }}</td>
              <td class="mono" :style="{ color: q.maxWaiting !== null && q.waitingCount >= q.maxWaiting ? 'var(--fail)' : 'var(--t1)' }">
                {{ q.waitingCount }}
              </td>
              <td class="mono">{{ q.maxWaiting ?? '不限' }}</td>
            </tr>
            <tr v-if="backlogs.length === 0"><td colspan="3" class="empty-hint">暂无队列</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 资源使用 -->
      <div class="panel">
        <div class="panel-h"><b>资源使用（运行中任务占用 / 集群标称容量）</b></div>
        <table class="tbl">
          <thead>
            <tr><th>集群</th><th>CPU</th><th>GPU</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in resources" :key="r.clusterId">
              <td>{{ r.clusterName }}</td>
              <td class="mono">{{ r.cpuUsed }} / {{ r.cpuTotal }} 核</td>
              <td class="mono">{{ r.gpuUsed }} / {{ r.gpuTotal }} 卡</td>
            </tr>
            <tr v-if="resources.length === 0"><td colspan="3" class="empty-hint">暂无集群</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 集群健康 -->
      <div class="panel">
        <div class="panel-h"><b>集群健康</b></div>
        <table class="tbl">
          <thead>
            <tr><th>集群</th><th>状态</th><th>节点</th><th>运行任务</th></tr>
          </thead>
          <tbody>
            <tr v-for="c in clusters" :key="c.clusterId">
              <td>{{ c.clusterName }}</td>
              <td><ToneChip :tone="c.status === 'NORMAL' ? 'ok' : c.status === 'MAINTENANCE' ? 'mut' : 'fail'" :label="c.status" /></td>
              <td class="mono">{{ c.nodeOnline }}/{{ c.nodeTotal }}</td>
              <td class="mono">{{ c.runningTasks }}</td>
            </tr>
            <tr v-if="clusters.length === 0"><td colspan="4" class="empty-hint">暂无集群</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 最近失败 -->
      <div class="panel">
        <div class="panel-h"><b>最近失败任务</b></div>
        <table class="tbl">
          <thead>
            <tr><th>任务</th><th>状态</th><th>失败原因</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="f in failures" :key="f.taskId">
              <td>
                <router-link class="link-id" :to="`/tasks/${f.taskId}`">{{ f.taskId }}</router-link>
                <div class="sub">{{ f.workflowName }}</div>
              </td>
              <td><ToneChip :tone="statusTone(f.status)" :label="statusLabel(f.status)" /></td>
              <td class="mono fail-reason" :title="f.failReason ?? ''">{{ dash(f.failReason) }}</td>
              <td class="mono">{{ formatDateTime(f.updatedAt) }}</td>
            </tr>
            <tr v-if="failures.length === 0"><td colspan="4" class="empty-hint">暂无失败任务</td></tr>
          </tbody>
        </table>
      </div>

      <!-- 最新告警 -->
      <div class="panel">
        <div class="panel-h"><b>最新告警</b></div>
        <table class="tbl">
          <thead>
            <tr><th>内容</th><th>级别</th><th>结果</th><th>时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="a in alerts" :key="a.alertId">
              <td>
                <div>{{ a.content }}</div>
                <div class="sub mono">{{ a.channels.join('、') }}</div>
              </td>
              <td><ToneChip :tone="levelTone(a.level)" :label="levelLabel(a.level)" /></td>
              <td><ToneChip :tone="a.result === 'SENT' ? 'ok' : a.result === 'PARTIAL' ? 'warn' : 'fail'" :label="a.result" /></td>
              <td class="mono">{{ formatDateTime(a.firedAt) }}</td>
            </tr>
            <tr v-if="alerts.length === 0"><td colspan="4" class="empty-hint">暂无告警</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.link-id {
  color: var(--t1);
  font-weight: 600;
  text-decoration: none;
}

.link-id:hover {
  color: var(--pri-hi);
}

.empty-hint {
  text-align: center;
  color: var(--t3);
  padding: 22px;
  font-size: 12px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}

.card {
  background: var(--bg-panel);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-md);
  padding: 14px 16px;
}

.card-label {
  font-size: 11.5px;
  color: var(--t3);
}

.card-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--t1);
  margin: 6px 0 2px;
}

.card-sub {
  font-size: 11px;
  color: var(--t3);
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  align-items: start;
}

.trend-wrap {
  padding: 10px 14px;
}

.trend-svg {
  width: 100%;
  height: auto;
}

.axis {
  stroke: var(--line-faint);
  stroke-width: 1;
}

.bar-ok {
  fill: var(--ok);
  opacity: 0.85;
}

.bar-fail {
  fill: var(--fail);
  opacity: 0.85;
}

.tick {
  font-size: 9px;
  fill: var(--t3);
}

.val {
  font-size: 9px;
  fill: var(--t2);
}

.legend {
  display: flex;
  gap: 16px;
  font-size: 11.5px;
  color: var(--t3);
  margin-top: 4px;
}

.legend .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 5px;
}

.dot.ok {
  background: var(--ok);
}

.dot.fail {
  background: var(--fail);
}

.fail-reason {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1100px) {
  .cards {
    grid-template-columns: repeat(3, 1fr);
  }

  .grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>
