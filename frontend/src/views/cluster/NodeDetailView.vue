<script setup lang="ts">
/**
 * 节点详情（M2 实装；原型 prototype/node-detail.html）。
 *
 * 【本页演示的机制】
 * - 「只读区 vs 可编辑区」用类型区分，而不是靠界面提示：
 *   在线状态/心跳/实际用量在后端入参 DTO 里根本没有字段（SaveExecutorNodeParams 也没有），
 *   所以界面上它们只能是文本，想"改成在线"得先改后端契约 —— 用类型系统固化业务规则。
 * - 连通性测试：真实 SSH 握手。失败是**正常返回值**（success=false + 耗时），
 *   不是接口异常；所以这里判断的是 result.success，而不是 try/catch。
 * - 编辑弹窗里的凭据下拉只取第一页（200 条）：凭据数是"人手维护"量级，
 *   为它做远程搜索是过度设计；真超过 200 条时下拉会显示提示，再换远程搜索也不迟。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { nodeApi } from '@/api/modules/cluster'
import { credentialApi } from '@/api/modules/credential'
import type { ExecutorNodeItem, SaveExecutorNodeParams } from '@/api/types/cluster'
import type { CredentialItem } from '@/api/types/credential'
import ToneChip from '@/components/biz/ToneChip.vue'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  NODE_ONLINE_LABEL,
  NODE_ONLINE_TONE,
  type NodeOnlineStatus,
  type Tone,
} from '@/types/enums'
import { formatDateTime, formatHeartbeat, formatMb } from '@/utils/format'

const route = useRoute()
const { can } = usePermission()

const nodeId = computed(() => String(route.params.nodeId))
const node = ref<ExecutorNodeItem | null>(null)
const testing = ref(false)

const onlineLabel = (s: string) => NODE_ONLINE_LABEL[s as NodeOnlineStatus] ?? s
const onlineTone = (s: string): Tone => NODE_ONLINE_TONE[s as NodeOnlineStatus] ?? 'idle'

/** 资源使用率（用/总），总数为 0 时返回 0 而不是 NaN —— 新建设备常常还没填容量 */
function usage(used?: number | null, total?: number | null): number {
  if (!used || !total) return 0
  return Math.min(100, Math.round((used / total) * 100))
}

async function load() {
  node.value = await nodeApi.get(nodeId.value)
}

onMounted(load)
watch(nodeId, load)

// ── 连通性测试 ──────────────────────────────────────────────
async function runTest() {
  if (!node.value) return
  testing.value = true
  try {
    const result = await nodeApi.test(nodeId.value)
    const text = `${result.message}（耗时 ${result.elapsedMs} ms）`
    if (result.success) ElMessage.success(text)
    else ElMessage.warning(text)
  } finally {
    testing.value = false
  }
}

// ── 启停 ────────────────────────────────────────────────────
async function toggleEnabled() {
  if (!node.value) return
  await nodeApi.setEnabled(nodeId.value, !node.value.enabled)
  ElMessage.success(node.value.enabled ? '节点已禁用' : '节点已启用')
  await load()
}

// ── 编辑（心跳类字段不在表单里，见文件头注释）────────────────
const dialogVisible = ref(false)
const saving = ref(false)
const credentials = ref<CredentialItem[]>([])
const form = ref<SaveExecutorNodeParams>({
  executorNodeName: '',
  ip: '',
  osType: 'LINUX',
  connectType: 'SSH',
  credentialId: null,
  tags: [],
  maxConcurrentSteps: null,
  cpuTotal: 0,
  memoryTotal: 0,
})

async function openEdit() {
  if (!node.value) return
  form.value = {
    executorNodeName: node.value.executorNodeName,
    clusterId: node.value.clusterId,
    ip: node.value.ip,
    osType: node.value.osType,
    connectType: node.value.connectType,
    credentialId: node.value.credentialId,
    tags: [...node.value.tags],
    maxConcurrentSteps: node.value.maxConcurrentSteps,
    cpuTotal: node.value.cpuTotal,
    memoryTotal: node.value.memoryTotal,
    enabled: node.value.enabled,
  }
  if (credentials.value.length === 0) {
    const page = await credentialApi.page({ page: 1, pageSize: 200 })
    credentials.value = page.records
  }
  dialogVisible.value = true
}

async function submit() {
  if (!form.value.executorNodeName.trim() || !form.value.ip.trim()) {
    ElMessage.warning('节点名称与 IP 必填')
    return
  }
  saving.value = true
  try {
    await nodeApi.update(nodeId.value, form.value)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="node">
    <div class="page-header">
      <div>
        <h1>
          {{ node.executorNodeName }}
          <ToneChip :tone="onlineTone(node.onlineStatus)" :label="onlineLabel(node.onlineStatus)" />
          <ToneChip :tone="node.enabled ? 'ok' : 'idle'" :label="node.enabled ? '启用' : '禁用'" />
        </h1>
        <div class="sub">
          <span class="mono">{{ node.executorNodeId }}</span>
          <span>· {{ node.ip }}</span>
          <span>· {{ node.osType }} / {{ node.connectType }}</span>
        </div>
      </div>
      <div class="actions">
        <el-button @click="load">刷新</el-button>
        <el-button v-if="can(PERM.NODE_TEST)" :loading="testing" @click="runTest">测试连通性</el-button>
        <el-button v-if="can(PERM.NODE_WRITE)" @click="toggleEnabled">
          {{ node.enabled ? '禁用节点' : '启用节点' }}
        </el-button>
        <el-button v-if="can(PERM.NODE_WRITE)" type="primary" @click="openEdit">编辑</el-button>
        <router-link :to="`/clusters/${node.clusterId}`">
          <el-button>所属集群</el-button>
        </router-link>
      </div>
    </div>

    <div class="panel" style="margin-bottom: 14px">
      <div class="panel-h">
        <b>心跳</b>
        <span class="hint">由节点上报链路维护，接口只读（15s 上报 / 45s 判离线 / 5min 强离线）</span>
      </div>
      <div class="panel-b stat-grid">
        <div class="stat">
          <span class="k">在线状态</span>
          <span class="v">
            <ToneChip :tone="onlineTone(node.onlineStatus)" :label="onlineLabel(node.onlineStatus)" />
          </span>
          <span class="s">未上报 = 从未收到过心跳（新建节点的初始态）</span>
        </div>
        <div class="stat">
          <span class="k">最近心跳</span>
          <span class="v" style="font-size: 14px">{{ formatHeartbeat(node.lastHeartbeatAt) }}</span>
          <span class="s mono">{{ formatDateTime(node.lastHeartbeatAt) }}</span>
        </div>
        <div class="stat">
          <span class="k">心跳丢包计数</span>
          <span class="v mono">{{ node.heartbeatMissCount }}</span>
          <span class="s">单次缺失仅展示，达阈值才判离线</span>
        </div>
        <div class="stat">
          <span class="k">最近被分配任务</span>
          <span class="v" style="font-size: 14px">{{ formatDateTime(node.lastAllocatedAt) }}</span>
          <span class="s">节点选择第 3 排序键（PRD §12.2）</span>
        </div>
      </div>
    </div>

    <div class="panel" style="margin-bottom: 14px">
      <div class="panel-h">
        <b>资源水位</b>
        <span class="hint">实际用量只做画像与偏差告警；准入判定用预留账本（D-22）</span>
      </div>
      <div class="panel-b">
        <div class="bar-row">
          <span class="bar-k">CPU</span>
          <div class="bar"><i :style="{ width: usage(node.cpuUsed, node.cpuTotal) + '%' }" /></div>
          <span class="bar-v mono">{{ node.cpuUsed }} / {{ node.cpuTotal }} 核</span>
        </div>
        <div class="bar-row">
          <span class="bar-k">内存</span>
          <div class="bar"><i :style="{ width: usage(node.memoryUsed, node.memoryTotal) + '%' }" /></div>
          <span class="bar-v mono">{{ formatMb(node.memoryUsed) }} / {{ formatMb(node.memoryTotal) }}</span>
        </div>
        <div class="bar-row">
          <span class="bar-k">磁盘</span>
          <div class="bar"><i :style="{ width: usage(node.diskUsed, node.diskTotal) + '%' }" /></div>
          <span class="bar-v mono">{{ formatMb(node.diskUsed) }} / {{ formatMb(node.diskTotal) }}</span>
        </div>
        <div class="bar-row">
          <span class="bar-k">GPU</span>
          <div class="bar"><i :style="{ width: usage(node.gpuUsed, node.gpuTotal) + '%' }" /></div>
          <span class="bar-v mono">{{ node.gpuUsed }} / {{ node.gpuTotal }} 卡</span>
        </div>
        <div class="kv-grid">
          <div><span class="k">运行中步骤</span><span class="v mono">{{ node.runningTaskCount }}</span></div>
          <div>
            <span class="k">最大并发步骤数</span>
            <span class="v mono">{{ node.maxConcurrentSteps ?? '不限制' }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-h"><b>归属与标签</b></div>
      <div class="panel-b">
        <div class="kv-grid">
          <div><span class="k">所属集群</span><span class="v">{{ node.clusterName }}</span></div>
          <div><span class="k">集群编号</span><span class="v mono">{{ node.clusterId }}</span></div>
          <div><span class="k">绑定凭据</span><span class="v">{{ node.credentialName ?? '未绑定' }}</span></div>
          <div><span class="k">创建时间</span><span class="v mono">{{ formatDateTime(node.createdAt) }}</span></div>
        </div>
        <div class="tags-line">
          <span class="k">标签</span>
          <span v-if="node.tags.length === 0" style="color: var(--t3)">无（该节点不参与标签约束调度）</span>
          <span v-for="tag in node.tags" :key="tag" class="tag-pill">{{ tag }}</span>
        </div>
      </div>
    </div>

    <!-- ── 编辑弹窗 ──────────────────────────────────────── -->
    <el-dialog v-model="dialogVisible" title="编辑执行节点" width="560px">
      <el-form label-position="top">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="节点名称" required>
              <el-input v-model="form.executorNodeName" maxlength="128" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="IP" required>
              <el-input v-model="form.ip" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="操作系统">
              <el-select v-model="form.osType" style="width: 100%">
                <el-option label="Linux" value="LINUX" />
                <el-option label="Windows" value="WINDOWS" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="连接方式">
              <el-select v-model="form.connectType" style="width: 100%">
                <el-option label="SSH" value="SSH" />
                <el-option label="WinRM" value="WINRM" />
                <el-option label="Agent" value="AGENT" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="绑定凭据（连通性测试与任务下发都用它）">
          <el-select v-model="form.credentialId" clearable placeholder="不绑定" style="width: 100%">
            <el-option
              v-for="cred in credentials"
              :key="cred.credentialId"
              :label="`${cred.credentialName}（${cred.credentialType} · ${cred.secretFingerprint}）`"
              :value="cred.credentialId"
            />
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="CPU 核数">
              <el-input-number v-model="form.cpuTotal" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="内存（MB）">
              <el-input-number v-model="form.memoryTotal" :min="0" :step="1024" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="标签">
          <el-select
            v-model="form.tags"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="最大并发步骤数（留空 = 不限制）">
          <el-input-number v-model="form.maxConcurrentSteps" :min="1" style="width: 100%" />
        </el-form-item>
        <div class="form-hint">
          在线状态 / 心跳 / 实际用量不在本表单内 —— 它们是上报链路的事实，不提供人工编辑入口。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
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

.bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.bar-k {
  width: 48px;
  font-size: 12px;
  color: var(--t3);
}

.bar {
  flex: 1;
  height: 6px;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: 999px;
  overflow: hidden;
}

.bar i {
  display: block;
  height: 100%;
  background: var(--pri);
  border-radius: 999px;
  transition: width var(--dur) var(--ease);
}

.bar-v {
  width: 170px;
  text-align: right;
  font-size: 11.5px;
  color: var(--t2);
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px 16px;
  margin-top: 12px;
}

.kv-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.kv-grid .k,
.tags-line .k {
  font-size: 11px;
  color: var(--t3);
}

.kv-grid .v {
  font-size: 13px;
  color: var(--t1);
}

.tags-line {
  margin-top: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tag-pill {
  display: inline-block;
  padding: 1px 7px;
  font-size: 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--t2);
  background: var(--bg-raise);
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
