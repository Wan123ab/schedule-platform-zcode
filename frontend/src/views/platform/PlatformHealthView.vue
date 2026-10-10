<script setup lang="ts">
/**
 * 平台健康度（第 19 页，D-24；docs/07 §6.7 v3 评审新增，08 §5.4）。
 *
 * 【口径（O-71）】scheduler 段锁推导降级：schedulerAlive = 选主锁存在、
 * leaderId = LeaderElector 发布的实例标识、lease_remaining_seconds = 锁 TTL；
 * is_leader / standby_count / last_tick_at / slow_query_count_1m / log_ingest_bytes
 * 一期不可得显示 "—"（不是隐藏 —— 让运维知道"这项有监控点，暂时没数据"）。
 * 任一后端故障不 500：后端已全段吞异常，前端只做渲染兜底。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { dashboardApi } from '@/api/modules/dashboard'
import type { PlatformHealth } from '@/api/types/dashboard'
import ToneChip from '@/components/biz/ToneChip.vue'
import { dash, formatDateTime } from '@/utils/format'

const health = ref<PlatformHealth | null>(null)
const loading = ref(true)

async function refresh() {
  try {
    health.value = await dashboardApi.platformHealth()
  } finally {
    loading.value = false
  }
}
onMounted(refresh)

// 30s 轮询（健康页低频足够；组件卸载清理）
const pollTimer = window.setInterval(refresh, 30_000)
onUnmounted(() => window.clearInterval(pollTimer))

const schedulerAlive = computed(() => health.value?.scheduler.schedulerAlive ?? false)
const redisConnected = computed(() => health.value?.redis.connected ?? false)

function formatBytes(bytes: number | null): string {
  if (bytes === null || bytes === undefined) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let v = bytes
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(1)} ${units[i]}`
}
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h1>平台健康度</h1>
        <div class="sub"><span>调度器 / 数据库 / 缓存 / 节点 / 队列 全量指标（07 §6.7 · 刷新间隔 30s）</span></div>
      </div>
      <div class="actions">
        <el-button @click="refresh">刷新</el-button>
      </div>
    </div>

    <div v-if="health" class="grid-3">
      <!-- 调度器 -->
      <div class="panel">
        <div class="panel-h">
          <b>调度器</b>
          <ToneChip :tone="schedulerAlive ? 'ok' : 'fail'" :label="schedulerAlive ? '运行中' : '不可达'" />
        </div>
        <div class="kv"><span>主调度器实例</span><b class="mono">{{ health.scheduler.leaderId ?? '—' }}</b></div>
        <div class="kv"><span>租约剩余</span><b class="mono">{{ dash(health.scheduler.leaseRemainingSeconds !== null ? `${health.scheduler.leaseRemainingSeconds}s` : null) }}</b></div>
        <div class="kv"><span>是否本机为主</span><b>{{ dash(health.scheduler.isLeader !== null ? String(health.scheduler.isLeader) : null) }}</b></div>
        <div class="kv"><span>最近 tick</span><b class="mono">{{ dash(health.scheduler.lastTickAt ? formatDateTime(health.scheduler.lastTickAt) : null) }}</b></div>
        <div class="kv"><span>tick 间隔</span><b class="mono">{{ dash(health.scheduler.tickIntervalMs !== null ? `${health.scheduler.tickIntervalMs}ms` : null) }}</b></div>
        <div class="kv"><span>standby 数</span><b class="mono">{{ dash(health.scheduler.standbyCount) }}</b></div>
      </div>

      <!-- 数据库 -->
      <div class="panel">
        <div class="panel-h"><b>数据库连接池</b></div>
        <div class="kv"><span>活跃 / 空闲</span><b class="mono">{{ health.db.poolActive }} / {{ health.db.poolIdle }}</b></div>
        <div class="kv"><span>池上限</span><b class="mono">{{ health.db.poolMax }}</b></div>
        <div class="kv"><span>近 1 分钟慢查询</span><b class="mono">{{ dash(health.db.slowQueryCount1m) }}</b></div>
        <div class="kv">
          <span>Redis 连接</span>
          <b><ToneChip :tone="redisConnected ? 'ok' : 'fail'" :label="redisConnected ? '已连接' : '不可达'" /></b>
        </div>
        <div class="kv"><span>Redis 内存占用</span><b class="mono">{{ health.redis.usedMemoryHuman ?? '—' }}</b></div>
        <div class="kv"><span>淘汰策略</span><b class="mono">{{ health.redis.evictionPolicy ?? '—' }}</b></div>
      </div>

      <!-- 节点 -->
      <div class="panel">
        <div class="panel-h"><b>执行节点</b></div>
        <div class="kv"><span>在线 / 总数</span><b class="mono">{{ health.nodes.online }} / {{ health.nodes.total }}</b></div>
        <div class="kv"><span>在线率</span><b class="mono">{{ (health.nodes.onlineRate * 100).toFixed(1) }}%</b></div>
        <div class="kv"><span>离线</span><b class="mono" :style="{ color: health.nodes.offline > 0 ? 'var(--fail)' : 'var(--t1)' }">{{ health.nodes.offline }}</b></div>
        <div class="kv"><span>待领取深度</span><b class="mono">{{ dash(health.nodes.queueDepth) }}</b></div>
        <div class="kv"><span>今日派发 / 失败</span><b class="mono">{{ health.today.dispatchedCount }} / {{ health.today.failedCount }}</b></div>
        <div class="kv"><span>日志摄取量</span><b class="mono">{{ formatBytes(health.today.logIngestBytes) }}</b></div>
      </div>

      <!-- 队列水位（整行） -->
      <div class="panel span-3">
        <div class="panel-h"><b>队列水位</b></div>
        <table class="tbl">
          <thead>
            <tr>
              <th>队列</th>
              <th>等待</th>
              <th>运行</th>
              <th>并发上限</th>
              <th>水位</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="q in health.queues" :key="q.queueId">
              <td>{{ q.name }}</td>
              <td class="mono">{{ q.waiting }}</td>
              <td class="mono">{{ q.running }}</td>
              <td class="mono">{{ q.maxConcurrentTasks ?? '不限' }}</td>
              <td>
                <div class="water">
                  <div
                    class="water-bar"
                    :class="q.waterLevel >= 0.9 ? 'high' : q.waterLevel >= 0.7 ? 'mid' : 'low'"
                    :style="{ width: `${Math.min(100, q.waterLevel * 100)}%` }"
                  />
                </div>
                <span class="mono water-text">{{ (q.waterLevel * 100).toFixed(0) }}%</span>
              </td>
            </tr>
            <tr v-if="health.queues.length === 0">
              <td colspan="5" class="empty-hint">暂无队列</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 最近告警（整行） -->
      <div class="panel span-3">
        <div class="panel-h"><b>最近告警</b></div>
        <table class="tbl">
          <thead>
            <tr><th>告警</th><th>级别</th><th>触发时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="a in health.recentAlerts" :key="a.alertId">
              <td>{{ a.title }}</td>
              <td><ToneChip :tone="a.level === 'CRITICAL' ? 'fail' : a.level === 'WARN' ? 'warn' : 'info'" :label="a.level" /></td>
              <td class="mono">{{ formatDateTime(a.firedAt) }}</td>
            </tr>
            <tr v-if="health.recentAlerts.length === 0">
              <td colspan="3" class="empty-hint">暂无告警</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div v-else-if="!loading" class="empty-hint page-empty">健康度数据不可用（后端暂时不可达）</div>
  </div>
</template>

<style scoped>
.empty-hint {
  text-align: center;
  color: var(--t3);
  padding: 22px;
  font-size: 12px;
}

.page-empty {
  padding: 80px 0;
}

.grid-3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  align-items: start;
}

.span-3 {
  grid-column: span 3;
}

.panel-h {
  display: flex;
  align-items: center;
  gap: 10px;
}

.kv {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 14px;
  font-size: 12px;
  color: var(--t3);
  border-bottom: 1px solid var(--line-faint);
}

.kv b {
  color: var(--t1);
  font-weight: 600;
}

.water {
  display: inline-block;
  width: 140px;
  height: 8px;
  background: var(--bg-inset);
  border-radius: 4px;
  overflow: hidden;
  vertical-align: middle;
  margin-right: 8px;
}

.water-bar {
  height: 100%;
  border-radius: 4px;
}

.water-bar.low {
  background: var(--ok);
}

.water-bar.mid {
  background: var(--warn);
}

.water-bar.high {
  background: var(--fail);
}

.water-text {
  font-size: 11px;
  color: var(--t2);
  vertical-align: middle;
}

@media (max-width: 1100px) {
  .grid-3 {
    grid-template-columns: 1fr;
  }

  .span-3 {
    grid-column: span 1;
  }
}
</style>
