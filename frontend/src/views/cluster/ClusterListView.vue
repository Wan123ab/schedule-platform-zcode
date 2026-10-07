<script setup lang="ts">
/**
 * 集群列表（M2 实装；原型 prototype/cluster-list.html，PRD §10.3）。
 *
 * 【本页演示的机制】
 * - useListQuery composable：把「页码/关键词/loading/拉取」这套列表状态机从页面里抽走。
 *   页面只剩"筛选条件 + 渲染 + 动作"三件事 —— 这是组合式 API 最实用的收益：
 *   逻辑被抽成可复用函数，而状态仍然是每页私有的（不会互相串）。
 * - ToneChip + 映射表：状态颜色/文案只从 types/enums.ts 查表（F-5），
 *   页面里没有一处 `if (status === 'NORMAL')`。
 * - 删除按钮置灰而不是隐藏：让用户看到"这个操作存在但当前不可用"，
 *   比"按钮凭空消失"更好理解 —— 真正拦得住的是服务端 42204（前端校验永远不是安全边界）。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clusterApi } from '@/api/modules/cluster'
import type { ClusterItem, SaveClusterParams } from '@/api/types/cluster'
import ToneChip from '@/components/biz/ToneChip.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  CLUSTER_STATUS_LABEL,
  CLUSTER_STATUS_TONE,
  type ClusterStatus,
  type Tone,
} from '@/types/enums'
import { formatDateTime, formatHeartbeat, formatMb } from '@/utils/format'

const { can } = usePermission()

const statusFilter = ref('')

const { items, total, page, pageSize, keyword, loading, refresh } = useListQuery<ClusterItem>((params) =>
  clusterApi.page({ ...params, status: statusFilter.value || undefined }),
)

onMounted(refresh)

// ── 状态查表（页面只做"查表"，不做颜色判定）──────────────────
const statusLabel = (status: string): string => CLUSTER_STATUS_LABEL[status as ClusterStatus] ?? status
const statusTone = (status: string): Tone => CLUSTER_STATUS_TONE[status as ClusterStatus] ?? 'idle'

// ── 分页（原型用的是轻量 pager，不用 el-pagination 以保持视觉一致）──
const totalPages = () => Math.max(1, Math.ceil(total.value / pageSize.value))
function go(target: number) {
  if (target < 1 || target > totalPages()) return
  page.value = target
  refresh()
}

// ── 创建 / 编辑弹窗（同一弹窗，用 editingId 区分模式）──────────
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const form = ref<SaveClusterParams>({
  clusterName: '',
  clusterType: 'GENERAL',
  cpuTotal: 0,
  gpuTotal: 0,
  memoryTotal: 0,
  diskTotal: 0,
})

function openCreate() {
  editingId.value = null
  form.value = { clusterName: '', clusterType: 'GENERAL', cpuTotal: 0, gpuTotal: 0, memoryTotal: 0, diskTotal: 0 }
  dialogVisible.value = true
}

function openEdit(item: ClusterItem) {
  editingId.value = item.clusterId
  form.value = {
    clusterName: item.clusterName,
    clusterType: item.clusterType,
    cpuTotal: item.cpuTotal,
    gpuTotal: item.gpuTotal,
    memoryTotal: item.memoryTotal,
    diskTotal: item.diskTotal,
  }
  dialogVisible.value = true
}

async function submitForm() {
  if (!form.value.clusterName.trim()) {
    ElMessage.warning('集群名称必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await clusterApi.update(editingId.value, form.value)
      ElMessage.success('已保存')
    } else {
      await clusterApi.create(form.value)
      ElMessage.success('集群已创建')
    }
    dialogVisible.value = false
    refresh()
  } finally {
    saving.value = false
  }
}

// ── 维护开关 / 删除 ─────────────────────────────────────────
async function toggleMaintenance(item: ClusterItem) {
  const toMaintenance = item.status !== 'MAINTENANCE'
  const tip = toMaintenance
    ? `维护模式会让「${item.clusterName}」停止接纳新任务（已在跑的不受影响）。确认切换？`
    : `将「${item.clusterName}」恢复为正常状态并重新接纳新任务？`
  await ElMessageBox.confirm(tip, toMaintenance ? '进入维护模式' : '退出维护模式', {
    type: 'warning',
    confirmButtonText: '确认',
    cancelButtonText: '取消',
  })
  await clusterApi.updateStatus(item.clusterId, toMaintenance ? 'MAINTENANCE' : 'NORMAL')
  ElMessage.success('状态已更新')
  refresh()
}

async function remove(item: ClusterItem) {
  await ElMessageBox.confirm(
    `确认删除集群「${item.clusterName}」？集群下若还有执行节点或队列，服务端会拒绝并给出原因。`,
    '删除集群',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
  await clusterApi.remove(item.clusterId)
  ElMessage.success('已删除')
  refresh()
}
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>集群管理</h1>
        <div class="sub">
          <span>执行节点集合 · 调度资源池的顶层单元（PRD §10.3）</span>
          <span class="mono">共 {{ total }} 个集群</span>
        </div>
      </div>
      <div class="actions">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="go(1)">
          <el-option label="正常" value="NORMAL" />
          <el-option label="部分异常" value="PARTIAL_ABNORMAL" />
          <el-option label="不可用" value="UNAVAILABLE" />
          <el-option label="维护中" value="MAINTENANCE" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索集群名"
          style="width: 200px"
          clearable
          @keyup.enter="go(1)"
        />
        <el-button @click="go(1)">查询</el-button>
        <el-button v-if="can(PERM.CLUSTER_WRITE)" type="primary" @click="openCreate">新建集群</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="table-wrap" v-loading="loading">
        <table class="tbl">
          <thead>
            <tr>
              <th>集群</th>
              <th>状态</th>
              <th>节点（总/在线/离线/空闲）</th>
              <th>容量（CPU / 内存）</th>
              <th>任务（运行/待调度/历史）</th>
              <th>最近心跳</th>
              <th style="width: 210px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in items" :key="row.clusterId">
              <td>
                <router-link class="link-id" :to="`/clusters/${row.clusterId}`">{{ row.clusterName }}</router-link>
                <div class="sub mono">{{ row.clusterId }}</div>
              </td>
              <td><ToneChip :tone="statusTone(row.status)" :label="statusLabel(row.status)" /></td>
              <td class="mono">
                {{ row.nodeTotal }}
                <span style="color: var(--t4)">·</span> {{ row.nodeOnline }}
                <span style="color: var(--t4)">·</span> {{ row.nodeOffline }}
                <span style="color: var(--t4)">·</span> {{ row.nodeIdle }}
              </td>
              <td class="mono">{{ row.cpuTotal }} 核 / {{ formatMb(row.memoryTotal) }}</td>
              <td class="mono">{{ row.runningTaskCount }} / {{ row.pendingTaskCount }} / {{ row.historyTaskCount }}</td>
              <td class="mono" :title="formatDateTime(row.lastHeartbeatAt)">
                {{ formatHeartbeat(row.lastHeartbeatAt) }}
              </td>
              <td>
                <div class="actions">
                  <router-link :to="`/clusters/${row.clusterId}`">
                    <el-button text size="small">详情</el-button>
                  </router-link>
                  <el-button v-if="can(PERM.CLUSTER_WRITE)" text size="small" @click="openEdit(row)">编辑</el-button>
                  <el-button v-if="can(PERM.CLUSTER_WRITE)" text size="small" @click="toggleMaintenance(row)">
                    {{ row.status === 'MAINTENANCE' ? '退出维护' : '维护' }}
                  </el-button>
                  <el-button v-if="can(PERM.CLUSTER_WRITE)" text size="small" @click="remove(row)">删除</el-button>
                </div>
              </td>
            </tr>
            <tr v-if="items.length === 0">
              <td colspan="7" class="empty-hint">{{ loading ? '加载中…' : '暂无集群' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pager">
        <span>第 {{ page }} / {{ totalPages() }} 页</span>
        <div class="pgs">
          <button :disabled="page <= 1" @click="go(page - 1)">‹</button>
          <button class="on">{{ page }}</button>
          <button :disabled="page >= totalPages()" @click="go(page + 1)">›</button>
        </div>
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑集群' : '新建集群'"
      width="560px"
    >
      <el-form label-position="top">
        <el-form-item label="集群名称" required>
          <el-input v-model="form.clusterName" maxlength="128" placeholder="例如：生产集群A" />
        </el-form-item>
        <el-form-item label="集群类型">
          <el-select v-model="form.clusterType" style="width: 100%">
            <el-option label="通用（GENERAL）" value="GENERAL" />
            <el-option label="GPU 集群" value="GPU" />
            <el-option label="大数据集群" value="BIGDATA" />
            <el-option label="自定义" value="CUSTOM" />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="CPU 总算力（核）">
              <el-input-number v-model="form.cpuTotal" :min="0" :step="1" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="GPU 总算力（卡）">
              <el-input-number v-model="form.gpuTotal" :min="0" :step="1" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="内存总量（MB）">
              <el-input-number v-model="form.memoryTotal" :min="0" :step="1024" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="磁盘总量（MB）">
              <el-input-number v-model="form.diskTotal" :min="0" :step="10240" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <div class="form-hint">
          容量是"集群标称值"，用于展示与额度校验；真实余量由节点的预留账本汇总（docs/06 §5.1）。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
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
</style>
