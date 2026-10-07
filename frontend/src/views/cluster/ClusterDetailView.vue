<script setup lang="ts">
/**
 * 集群详情（M2 实装；原型 prototype/cluster-detail.html）。
 *
 * 【本页演示的机制】
 * - 两个 tab 各自独立分页：节点与队列是两套互相独立的列表状态，所以复用两遍 useListQuery，
 *   而不是共用一个 —— 共用会让切 tab 时页码互相污染（"我明明在第 3 页"）。
 * - `watch(clusterId, loadAll)`：地址栏从 /clusters/A 换到 /clusters/B 时组件**不会重建**
 *   （vue-router 复用同一组件实例），必须显式监听路由参数重新取数。
 *   这是"详情页里点开另一个详情"最常见的白屏来源。
 * - 删除/禁用失败不做前端"猜测"，而是把服务端原因透传（42204/42205）：
 *   集群下的节点数是聚合快照（docs/05 §6.3），判断权必须在服务端。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi, nodeApi, queueApi } from '@/api/modules/cluster'
import type {
  ClusterItem,
  ExecutorNodeItem,
  QueueItem,
  SaveExecutorNodeParams,
  SaveQueueParams,
} from '@/api/types/cluster'
import ToneChip from '@/components/biz/ToneChip.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  CLUSTER_STATUS_LABEL,
  CLUSTER_STATUS_TONE,
  NODE_ONLINE_LABEL,
  NODE_ONLINE_TONE,
  QUEUE_STATUS_LABEL,
  QUEUE_STATUS_TONE,
  type ClusterStatus,
  type NodeOnlineStatus,
  type QueueStatus,
  type Tone,
} from '@/types/enums'
import { formatDateTime, formatHeartbeat, formatMb } from '@/utils/format'

const route = useRoute()
const { can } = usePermission()

const clusterId = computed(() => String(route.params.clusterId))
const cluster = ref<ClusterItem | null>(null)
const activeTab = ref<'nodes' | 'queues'>('nodes')

// ── 状态查表 ────────────────────────────────────────────────
const statusLabel = (s: string) => CLUSTER_STATUS_LABEL[s as ClusterStatus] ?? s
const statusTone = (s: string): Tone => CLUSTER_STATUS_TONE[s as ClusterStatus] ?? 'idle'
const onlineLabel = (s: string) => NODE_ONLINE_LABEL[s as NodeOnlineStatus] ?? s
const onlineTone = (s: string): Tone => NODE_ONLINE_TONE[s as NodeOnlineStatus] ?? 'idle'
const queueLabel = (s: string) => QUEUE_STATUS_LABEL[s as QueueStatus] ?? s
const queueTone = (s: string): Tone => QUEUE_STATUS_TONE[s as QueueStatus] ?? 'idle'

// ── 数据加载 ────────────────────────────────────────────────
async function loadCluster() {
  cluster.value = await clusterApi.get(clusterId.value)
}

const nodeFilter = ref({ onlineStatus: '', osType: '', tag: '' })
const nodes = useListQuery<ExecutorNodeItem>((params) =>
  clusterApi.nodes(clusterId.value, {
    ...params,
    onlineStatus: nodeFilter.value.onlineStatus || undefined,
    osType: nodeFilter.value.osType || undefined,
    tag: nodeFilter.value.tag || undefined,
  }),
)

const queues = useListQuery<QueueItem>((params) =>
  clusterApi.queues(clusterId.value, { page: params.page, pageSize: params.pageSize }),
)

async function loadAll() {
  await Promise.all([loadCluster(), nodes.refresh(), queues.refresh()])
}

onMounted(loadAll)
watch(clusterId, loadAll)

const nodeTotalPages = computed(() => Math.max(1, Math.ceil(nodes.total.value / nodes.pageSize.value)))

function nodePrev() {
  if (nodes.page.value <= 1) return
  nodes.page.value -= 1
  void nodes.refresh()
}

function nodeNext() {
  if (nodes.page.value >= nodeTotalPages.value) return
  nodes.page.value += 1
  void nodes.refresh()
}

// ── 集群维护开关 ────────────────────────────────────────────
async function toggleMaintenance() {
  if (!cluster.value) return
  const toMaintenance = cluster.value.status !== 'MAINTENANCE'
  await ElMessageBox.confirm(
    toMaintenance
      ? `维护模式会让「${cluster.value.clusterName}」停止接纳新任务（已在跑的不受影响）。确认切换？`
      : `将「${cluster.value.clusterName}」恢复为正常状态并重新接纳新任务？`,
    toMaintenance ? '进入维护模式' : '退出维护模式',
    { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
  )
  await clusterApi.updateStatus(clusterId.value, toMaintenance ? 'MAINTENANCE' : 'NORMAL')
  ElMessage.success('状态已更新')
  await loadCluster()
}

// ── 节点：新增 / 启停 / 测试 / 删除 ─────────────────────────
const nodeDialogVisible = ref(false)
const savingNode = ref(false)
const nodeForm = ref<SaveExecutorNodeParams>(emptyNodeForm())

function emptyNodeForm(): SaveExecutorNodeParams {
  return {
    executorNodeName: '',
    ip: '',
    osType: 'LINUX',
    connectType: 'SSH',
    credentialId: null,
    tags: [],
    maxConcurrentSteps: null,
    cpuTotal: 0,
    memoryTotal: 0,
  }
}

function openCreateNode() {
  nodeForm.value = emptyNodeForm()
  nodeDialogVisible.value = true
}

async function submitNode() {
  if (!nodeForm.value.executorNodeName.trim() || !nodeForm.value.ip.trim()) {
    ElMessage.warning('节点名称与 IP 必填')
    return
  }
  savingNode.value = true
  try {
    await clusterApi.createNode(clusterId.value, nodeForm.value)
    ElMessage.success('节点已创建（在线状态为「未上报」，首次心跳后自动更新）')
    nodeDialogVisible.value = false
    await Promise.all([nodes.refresh(), loadCluster()])
  } finally {
    savingNode.value = false
  }
}

async function toggleNode(row: ExecutorNodeItem) {
  await nodeApi.setEnabled(row.executorNodeId, !row.enabled)
  ElMessage.success(row.enabled ? '节点已禁用' : '节点已启用')
  void nodes.refresh()
}

async function testNode(row: ExecutorNodeItem) {
  // 握手失败也是"结果"（后端 200 + success=false），故这里不做错误弹窗兜底
  const result = await nodeApi.test(row.executorNodeId)
  const text = `${result.message}（耗时 ${result.elapsedMs} ms）`
  if (result.success) ElMessage.success(text)
  else ElMessage.warning(text)
}

async function removeNode(row: ExecutorNodeItem) {
  await ElMessageBox.confirm(`确认删除节点「${row.executorNodeName}」？`, '删除节点', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
  await nodeApi.remove(row.executorNodeId)
  ElMessage.success('已删除')
  await Promise.all([nodes.refresh(), loadCluster()])
}

// ── 队列：新建 / 编辑 / 启停 / 删除 ─────────────────────────
const queueDialogVisible = ref(false)
const queueEditingId = ref<string | null>(null)
const savingQueue = ref(false)
const queueForm = ref<SaveQueueParams>(emptyQueueForm())

function emptyQueueForm(): SaveQueueParams {
  return {
    queueName: '',
    maxConcurrentTasks: 3,
    maxWaitingTasks: 50,
    defaultPriority: 0,
    allowJumpQueue: false,
    waitTimeoutSeconds: 3600,
  }
}

function openCreateQueue() {
  queueEditingId.value = null
  queueForm.value = emptyQueueForm()
  queueDialogVisible.value = true
}

function openEditQueue(row: QueueItem) {
  queueEditingId.value = row.queueId
  queueForm.value = {
    queueName: row.queueName,
    maxConcurrentTasks: row.maxConcurrentTasks,
    maxWaitingTasks: row.maxWaitingTasks,
    defaultPriority: row.defaultPriority,
    allowJumpQueue: row.allowJumpQueue,
    waitTimeoutSeconds: row.waitTimeoutSeconds,
  }
  queueDialogVisible.value = true
}

async function submitQueue() {
  if (!queueForm.value.queueName.trim()) {
    ElMessage.warning('队列名称必填')
    return
  }
  savingQueue.value = true
  try {
    if (queueEditingId.value) {
      await queueApi.update(queueEditingId.value, queueForm.value)
      ElMessage.success('已保存')
    } else {
      await clusterApi.createQueue(clusterId.value, queueForm.value)
      ElMessage.success('队列已创建')
    }
    queueDialogVisible.value = false
    await Promise.all([queues.refresh(), loadCluster()])
  } finally {
    savingQueue.value = false
  }
}

async function toggleQueue(row: QueueItem) {
  await queueApi.updateStatus(row.queueId, row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED')
  ElMessage.success('状态已更新')
  void queues.refresh()
}

async function removeQueue(row: QueueItem) {
  await ElMessageBox.confirm(`确认删除队列「${row.queueName}」？`, '删除队列', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
  await queueApi.remove(row.queueId)
  ElMessage.success('已删除')
  await Promise.all([queues.refresh(), loadCluster()])
}
</script>

<template>
  <div v-if="cluster">
    <div class="page-header">
      <div>
        <h1>
          {{ cluster.clusterName }}
          <ToneChip :tone="statusTone(cluster.status)" :label="statusLabel(cluster.status)" />
        </h1>
        <div class="sub">
          <span class="mono">{{ cluster.clusterId }}</span>
          <span>· {{ cluster.clusterType }}</span>
          <span>· 最近心跳 {{ formatHeartbeat(cluster.lastHeartbeatAt) }}</span>
        </div>
      </div>
      <div class="actions">
        <el-button @click="loadAll">刷新</el-button>
        <el-button v-if="can(PERM.CLUSTER_WRITE)" @click="toggleMaintenance">
          {{ cluster.status === 'MAINTENANCE' ? '退出维护' : '进入维护' }}
        </el-button>
        <router-link to="/clusters">
          <el-button>返回列表</el-button>
        </router-link>
      </div>
    </div>

    <!-- 概览（统计列是 30s 聚合快照，仅展示用；业务判定一律服务端实时 COUNT） -->
    <div class="panel" style="margin-bottom: 14px">
      <div class="panel-h">
        <b>集群概览</b>
        <span class="hint">统计列为 30 秒聚合快照，非实时值</span>
      </div>
      <div class="panel-b stat-grid">
        <div class="stat">
          <span class="k">节点</span>
          <span class="v mono">{{ cluster.nodeTotal }}</span>
          <span class="s mono">在线 {{ cluster.nodeOnline }} · 离线 {{ cluster.nodeOffline }} · 空闲 {{ cluster.nodeIdle }}</span>
        </div>
        <div class="stat">
          <span class="k">CPU / 内存</span>
          <span class="v mono">{{ cluster.cpuTotal }} 核</span>
          <span class="s mono">{{ formatMb(cluster.memoryTotal) }}</span>
        </div>
        <div class="stat">
          <span class="k">GPU / 磁盘</span>
          <span class="v mono">{{ cluster.gpuTotal }} 卡</span>
          <span class="s mono">{{ formatMb(cluster.diskTotal) }}</span>
        </div>
        <div class="stat">
          <span class="k">任务</span>
          <span class="v mono">{{ cluster.runningTaskCount }} 运行</span>
          <span class="s mono">待调度 {{ cluster.pendingTaskCount }} · 历史 {{ cluster.historyTaskCount }}</span>
        </div>
        <div class="stat">
          <span class="k">创建时间</span>
          <span class="v mono" style="font-size: 13px">{{ formatDateTime(cluster.createdAt) }}</span>
        </div>
      </div>
    </div>

    <div class="tabs">
      <button class="tab-btn" :class="{ on: activeTab === 'nodes' }" @click="activeTab = 'nodes'">
        执行节点 <span class="cnt">{{ nodes.total.value }}</span>
      </button>
      <button class="tab-btn" :class="{ on: activeTab === 'queues' }" @click="activeTab = 'queues'">
        调度队列 <span class="cnt">{{ queues.total.value }}</span>
      </button>
    </div>

    <!-- ── 节点 tab ──────────────────────────────────────── -->
    <div v-show="activeTab === 'nodes'" class="panel">
      <div class="panel-h">
        <b>执行节点</b>
        <span class="hint">标签（tags）用于调度期的标签约束（PRD §12.1）</span>
        <div class="h-actions">
          <el-select
            v-model="nodeFilter.onlineStatus"
            placeholder="全部状态"
            clearable
            style="width: 118px"
            @change="nodes.refresh"
          >
            <el-option label="在线" value="ONLINE" />
            <el-option label="离线" value="OFFLINE" />
            <el-option label="未上报" value="UNKNOWN" />
          </el-select>
          <el-select v-model="nodeFilter.osType" placeholder="全部系统" clearable style="width: 118px" @change="nodes.refresh">
            <el-option label="Linux" value="LINUX" />
            <el-option label="Windows" value="WINDOWS" />
          </el-select>
          <el-input
            v-model="nodeFilter.tag"
            placeholder="按标签过滤"
            style="width: 140px"
            clearable
            @keyup.enter="nodes.refresh"
          />
          <el-button v-if="can(PERM.NODE_WRITE)" type="primary" @click="openCreateNode">新增节点</el-button>
        </div>
      </div>
      <div v-loading="nodes.loading.value" class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th>节点</th>
              <th>在线</th>
              <th>IP / 连接</th>
              <th>凭据</th>
              <th>标签</th>
              <th>CPU（用/总）</th>
              <th>内存（用/总）</th>
              <th>启用</th>
              <th style="width: 240px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in nodes.items.value" :key="row.executorNodeId">
              <td>
                <router-link class="link-id" :to="`/executor-nodes/${row.executorNodeId}`">
                  {{ row.executorNodeName }}
                </router-link>
                <div class="sub mono">{{ row.executorNodeId }}</div>
              </td>
              <td><ToneChip :tone="onlineTone(row.onlineStatus)" :label="onlineLabel(row.onlineStatus)" /></td>
              <td class="mono">
                {{ row.ip }}
                <div class="sub">{{ row.osType }} · {{ row.connectType }}</div>
              </td>
              <td>{{ row.credentialName ?? '—' }}</td>
              <td>
                <span v-if="row.tags.length === 0" style="color: var(--t3)">—</span>
                <span v-for="tag in row.tags" :key="tag" class="tag-pill">{{ tag }}</span>
              </td>
              <td class="mono">{{ row.cpuUsed }} / {{ row.cpuTotal }}</td>
              <td class="mono">{{ formatMb(row.memoryUsed) }} / {{ formatMb(row.memoryTotal) }}</td>
              <td><ToneChip :tone="row.enabled ? 'ok' : 'idle'" :label="row.enabled ? '启用' : '禁用'" /></td>
              <td>
                <div class="actions">
                  <router-link :to="`/executor-nodes/${row.executorNodeId}`">
                    <el-button text size="small">详情</el-button>
                  </router-link>
                  <el-button v-if="can(PERM.NODE_TEST)" text size="small" @click="testNode(row)">测试</el-button>
                  <el-button v-if="can(PERM.NODE_WRITE)" text size="small" @click="toggleNode(row)">
                    {{ row.enabled ? '禁用' : '启用' }}
                  </el-button>
                  <el-button v-if="can(PERM.NODE_WRITE)" text size="small" @click="removeNode(row)">删除</el-button>
                </div>
              </td>
            </tr>
            <tr v-if="nodes.items.value.length === 0">
              <td colspan="9" class="empty-hint">{{ nodes.loading.value ? '加载中…' : '该集群暂无执行节点' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <span>共 {{ nodes.total.value }} 个节点 · 第 {{ nodes.page.value }} / {{ nodeTotalPages }} 页</span>
        <div class="pgs">
          <button :disabled="nodes.page.value <= 1" @click="nodePrev">‹</button>
          <button class="on">{{ nodes.page.value }}</button>
          <button :disabled="nodes.page.value >= nodeTotalPages" @click="nodeNext">›</button>
        </div>
      </div>
    </div>

    <!-- ── 队列 tab ──────────────────────────────────────── -->
    <div v-show="activeTab === 'queues'" class="panel">
      <div class="panel-h">
        <b>调度队列</b>
        <span class="hint">并发上限在「出队时」判定，等待上限在「提交时」判定（docs/06 §6.1）</span>
        <div class="h-actions">
          <el-button v-if="can(PERM.QUEUE_WRITE)" type="primary" @click="openCreateQueue">新建队列</el-button>
        </div>
      </div>
      <div v-loading="queues.loading.value" class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th>队列</th>
              <th>状态</th>
              <th>并发上限</th>
              <th>等待上限</th>
              <th>默认优先级</th>
              <th>允许插队</th>
              <th>排队超时</th>
              <th>等待中</th>
              <th style="width: 180px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in queues.items.value" :key="row.queueId">
              <td>
                <div class="link-id">{{ row.queueName }}</div>
                <div class="sub mono">{{ row.queueId }}</div>
              </td>
              <td><ToneChip :tone="queueTone(row.status)" :label="queueLabel(row.status)" /></td>
              <td class="mono">{{ row.maxConcurrentTasks }}</td>
              <td class="mono">{{ row.maxWaitingTasks }}</td>
              <td class="mono">{{ row.defaultPriority }}</td>
              <td>{{ row.allowJumpQueue ? '是' : '否' }}</td>
              <td class="mono">{{ row.waitTimeoutSeconds }} s</td>
              <td class="mono">{{ row.waitingTaskCount }}</td>
              <td>
                <div class="actions">
                  <el-button v-if="can(PERM.QUEUE_WRITE)" text size="small" @click="openEditQueue(row)">编辑</el-button>
                  <el-button v-if="can(PERM.QUEUE_WRITE)" text size="small" @click="toggleQueue(row)">
                    {{ row.status === 'ENABLED' ? '停用' : '启用' }}
                  </el-button>
                  <el-button v-if="can(PERM.QUEUE_WRITE)" text size="small" @click="removeQueue(row)">删除</el-button>
                </div>
              </td>
            </tr>
            <tr v-if="queues.items.value.length === 0">
              <td colspan="9" class="empty-hint">{{ queues.loading.value ? '加载中…' : '该集群暂无队列' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pager">
        <span>共 {{ queues.total.value }} 个队列</span>
      </div>
    </div>

    <!-- ── 新增节点弹窗 ──────────────────────────────────── -->
    <el-dialog v-model="nodeDialogVisible" title="新增执行节点" width="560px">
      <el-form label-position="top">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="节点名称" required>
              <el-input v-model="nodeForm.executorNodeName" maxlength="128" placeholder="例如：node-a" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="IP" required>
              <el-input v-model="nodeForm.ip" placeholder="10.0.0.11" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="操作系统">
              <el-select v-model="nodeForm.osType" style="width: 100%">
                <el-option label="Linux（支持远程执行）" value="LINUX" />
                <el-option label="Windows（一期仅登记展示，Q-02）" value="WINDOWS" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="连接方式">
              <el-select v-model="nodeForm.connectType" style="width: 100%">
                <el-option label="SSH（一期主流）" value="SSH" />
                <el-option label="WinRM" value="WINRM" />
                <el-option label="Agent（二期）" value="AGENT" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="CPU 核数">
              <el-input-number v-model="nodeForm.cpuTotal" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="内存（MB）">
              <el-input-number v-model="nodeForm.memoryTotal" :min="0" :step="1024" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="标签（输入后回车，调度期用于标签约束）">
          <el-select
            v-model="nodeForm.tags"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="例如：gpu / 高内存 / 生产"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="最大并发步骤数（留空 = 不限制）">
          <el-input-number v-model="nodeForm.maxConcurrentSteps" :min="1" style="width: 100%" />
        </el-form-item>
        <div class="form-hint">
          在线状态与心跳由上报链路维护，不需要（也不允许）人工设置；凭据可在节点详情页绑定。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="nodeDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingNode" @click="submitNode">保存</el-button>
      </template>
    </el-dialog>

    <!-- ── 新建 / 编辑队列弹窗 ───────────────────────────── -->
    <el-dialog v-model="queueDialogVisible" :title="queueEditingId ? '编辑队列' : '新建队列'" width="520px">
      <el-form label-position="top">
        <el-form-item label="队列名称" required>
          <el-input v-model="queueForm.queueName" maxlength="128" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="并发上限（出队时判定）">
              <el-input-number v-model="queueForm.maxConcurrentTasks" :min="1" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="等待上限（提交时判定）">
              <el-input-number v-model="queueForm.maxWaitingTasks" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="默认优先级（0~100）">
              <el-input-number v-model="queueForm.defaultPriority" :min="0" :max="100" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="排队超时（秒）">
              <el-input-number v-model="queueForm.waitTimeoutSeconds" :min="1" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="允许插队">
          <el-switch v-model="queueForm.allowJumpQueue" />
          <span class="inline-hint">开启后允许 enqueue_front 插队（互斥组任务除外）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="queueDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingQueue" @click="submitQueue">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.h-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.link-id {
  color: var(--t1);
  font-weight: 600;
  text-decoration: none;
}

.link-id:hover {
  color: var(--pri-hi);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
}

.stat .k {
  font-size: 11px;
  color: var(--t3);
}

.stat .v {
  font-size: 16px;
  color: var(--t1);
  font-weight: 600;
}

.stat .s {
  font-size: 11px;
  color: var(--t4);
}

.tag-pill {
  display: inline-block;
  margin: 1px 4px 1px 0;
  padding: 1px 7px;
  font-size: 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--t2);
  background: var(--bg-raise);
}

.empty-hint {
  text-align: center;
  color: var(--t3);
  padding: 30px;
}

.form-hint {
  font-size: 11.5px;
  color: var(--t3);
  line-height: 1.7;
  padding: 8px 10px;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
}

.inline-hint {
  margin-left: 10px;
  font-size: 11.5px;
  color: var(--t3);
}
</style>
