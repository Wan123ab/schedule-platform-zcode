<script setup lang="ts">
/**
 * 算子版本详情 + 试运行（PRD §10.6；原型 prototype/operator-version.html）。
 *
 * 【这一页的主功能是"试运行"，其余都是它的上下文】
 * 试运行的价值在于：**在把算子接进工作流之前**，先在一台真实节点上跑一遍，
 * 看着实时日志确认参数、命令、退出码都对。所以页面顺序是「配置 → 试运行」，
 * 参数模板与输出声明作为辅助信息并排呈现。
 *
 * 【几条必须讲清的产品口径（都落在 UI 文案里）】
 * ① 试运行**不产生任务记录**（PRD §10.6）—— 它只是"看一眼"，不占任务编号、不进任务列表；
 * ② 单次上限 10 分钟（600 秒），到点由服务端强制断开；
 * ③ `exit_code` 为空表示"进程没能确认启动"（连不上机器/认证失败/被超时杀掉），
 *    这时该查的是**机器是否可达**，不是命令写错了 —— 两种失败的提示方向不同；
 * ④ 成功与否按版本的 `success_codes` 判定，退出码不在其中就是失败（哪怕进程正常退出）。
 *
 * 【敏感参数的展示口径（M-07）】
 * `sensitive` 的参数：输入框是密码态；提交给**执行**的是真实值；但命令回显与日志里
 * 会被服务端替换成 `***`；它也**不允许**另存为版本默认值（默认值会明文出网）。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { clusterApi } from '@/api/modules/cluster'
import { operatorApi, operatorVersionApi } from '@/api/modules/operator'
import type { BizError } from '@/api/http'
import type { ClusterItem, ExecutorNodeItem } from '@/api/types/cluster'
import type { OperatorVersionItem, ParamDef } from '@/api/types/operator'
import ToneChip from '@/components/biz/ToneChip.vue'
import { useDryRunStream } from '@/composables/useDryRunStream'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  EXTRACT_MODE_LABEL,
  OS_TYPE_LABEL,
  PARAM_TYPE_LABEL,
  VERSION_PUBLISH_STATUS_LABEL,
  VERSION_PUBLISH_STATUS_TONE,
  type ExtractMode,
  type OsType,
  type ParamType,
  type Tone,
  type VersionPublishStatus,
} from '@/types/enums'
import { dash, formatDateTime, formatMb } from '@/utils/format'

const route = useRoute()
const { can } = usePermission()

const versionId = computed(() => String(route.params.versionId))
const operatorId = computed(() => String(route.params.operatorId))

const version = ref<OperatorVersionItem | null>(null)
const operatorName = ref('')
const references = ref<{ workflowId: string; workflowName: string; versionNo: string; stepName: string }[]>([])
const activeTab = ref('params')

const pubLabel = (s: string): string => VERSION_PUBLISH_STATUS_LABEL[s as VersionPublishStatus] ?? s
const pubTone = (s: string): Tone => VERSION_PUBLISH_STATUS_TONE[s as VersionPublishStatus] ?? 'idle'
const paramTypeLabel = (t: string): string => PARAM_TYPE_LABEL[t as ParamType] ?? t
const extractLabel = (m: string): string => EXTRACT_MODE_LABEL[m as ExtractMode] ?? m
const osLabel = (o: string): string => OS_TYPE_LABEL[o as OsType] ?? o

const paramTemplate = computed<ParamDef[]>(() => version.value?.paramTemplate ?? [])
const isDraft = computed(() => version.value?.publishStatus === 'DRAFT')

async function load() {
  version.value = await operatorVersionApi.get(versionId.value)
  const op = await operatorApi.get(operatorId.value)
  operatorName.value = op.operatorName
  references.value = await operatorVersionApi.references(versionId.value)
}

onMounted(async () => {
  await load()
  await loadClusters()
  resetParams()
})

// ═══════════════════════════════════════════════════════════
// 试运行：节点选择 → 参数 → 跑 → 看日志
// ═══════════════════════════════════════════════════════════

const clusters = ref<ClusterItem[]>([])
const nodes = ref<ExecutorNodeItem[]>([])
const clusterId = ref('')
const nodeId = ref('')
const timeoutSeconds = ref<number | undefined>(undefined)

async function loadClusters() {
  clusters.value = (await clusterApi.page({ page: 1, pageSize: 100 })).records
}

async function onClusterChange() {
  nodeId.value = ''
  nodes.value = clusterId.value
    ? (await clusterApi.nodes(clusterId.value, { page: 1, pageSize: 200 })).records
    : []
}

/** 只列"看起来能跑"的节点：Linux 且启用。真正的闸门在服务端（42200 带 rule 字段）。 */
const runnableNodes = computed(() =>
  nodes.value.filter((n) => n.osType === 'LINUX' && n.enabled !== false),
)

// ── 参数表单（按模板动态渲染）──────────────────────────────
/** 值保留原类型：NUMBER 存数字、BOOLEAN 存布尔 —— 后端刻意不做字符串化。 */
const paramValues = ref<Record<string, unknown>>({})

function resetParams() {
  const next: Record<string, unknown> = {}
  for (const p of paramTemplate.value) {
    next[p.paramKey] = defaultTypedValue(p)
  }
  paramValues.value = next
}

function defaultTypedValue(p: ParamDef): unknown {
  const raw = p.defaultValue
  if (raw === null || raw === '') return p.paramType === 'BOOLEAN' ? false : null
  if (p.paramType === 'NUMBER') return Number(raw)
  if (p.paramType === 'BOOLEAN') return raw === 'true'
  return raw
}

/** 提交给后端时剔掉"没填"的项：后端会把空串一视同仁地取默认值，不如不发。 */
function collectParams(): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const p of paramTemplate.value) {
    const v = paramValues.value[p.paramKey]
    if (v === null || v === undefined || (typeof v === 'string' && v.trim() === '')) continue
    out[p.paramKey] = v
  }
  return out
}

const {
  command,
  lines,
  eof,
  error: dryRunError,
  running,
  streamStarted,
  start,
  stop,
} = useDryRunStream()

const logBox = ref<HTMLElement | null>(null)

// 新日志到达就滚到底 —— 看实时日志时最烦的就是手动往下拖
watch(
  () => lines.value.length,
  async () => {
    await nextTick()
    if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight
  },
)

async function runDryRun() {
  if (!nodeId.value) {
    ElMessage.warning('请先选择执行节点')
    return
  }
  const params = collectParams()
  const missing = paramTemplate.value.filter((p) => p.required && !(p.paramKey in params))
  if (missing.length) {
    ElMessage.warning(`必填参数未填：${missing.map((p) => p.name).join('、')}`)
    return
  }
  await start(versionId.value, {
    executorNodeId: nodeId.value,
    params,
    timeoutSeconds: timeoutSeconds.value || undefined,
  })
}

/** EOF 帧的展示口径：退出码为空 ≠ 命令写错，而是"进程没能确认启动"。 */
const exitCodeText = computed(() => {
  const c = eof.value?.exitCode
  return c === null || c === undefined ? '未知' : String(c)
})
const exitHint = computed(() => {
  if (!eof.value) return ''
  if (eof.value.exitCode === null || eof.value.exitCode === undefined) {
    return '进程未能确认启动（连接/认证失败，或被超时强制终止）——请先确认节点是否可达，而不是改命令'
  }
  // success_codes 在 CMD 帧上（EOF 帧只给结论），所以从 command 取
  const codes = command.value?.successCodes ?? []
  return eof.value.success
    ? '退出码在成功码范围内'
    : `退出码 ${eof.value.exitCode} 不在成功码 ${codes.join('/')} 内`
})

// ── 把本次参数另存为版本默认值（PRD §10.6 后续动作）─────────
const savingDefaults = ref(false)

async function saveAsDefaults() {
  const params = collectParams()
  const sensitiveKeys = paramTemplate.value.filter((p) => p.sensitive).map((p) => p.paramKey)
  const payload: Record<string, string> = {}
  for (const [k, v] of Object.entries(params)) {
    if (sensitiveKeys.includes(k)) continue // 敏感参数后端也会拒，这里先跳过并在下面说明
    payload[k] = String(v)
  }
  if (Object.keys(payload).length === 0) {
    ElMessage.warning('没有可保存的参数值')
    return
  }
  savingDefaults.value = true
  try {
    await operatorVersionApi.saveParamDefaults(versionId.value, payload)
    if (sensitiveKeys.length) {
      ElMessage.success(`已保存 ${Object.keys(payload).length} 个默认值（${sensitiveKeys.length} 个敏感参数已跳过）`)
    } else {
      ElMessage.success('已另存为版本默认值')
    }
    await load()
  } catch (e) {
    if ((e as BizError).code === 42210) {
      ElMessage.warning('部分参数不在模板内，未保存任何默认值')
    }
  } finally {
    savingDefaults.value = false
  }
}

// ═══════════════════════════════════════════════════════════
// 参数模板编辑（仅草稿可改）
// ═══════════════════════════════════════════════════════════

const editVisible = ref(false)
const saving = ref(false)
const draftTemplate = ref<ParamDef[]>([])

function openEdit() {
  // 深拷贝：编辑期间不影响页面上已展示的数据
  draftTemplate.value = paramTemplate.value.map((p) => ({ ...p, options: p.options ? [...p.options] : null }))
  if (draftTemplate.value.length === 0) addParamRow()
  editVisible.value = true
}

function addParamRow() {
  draftTemplate.value.push({
    name: '',
    paramKey: '',
    paramType: 'TEXT',
    required: false,
    defaultValue: null,
    rule: null,
    help: null,
    runtimeOverridable: true,
    sensitive: false,
    options: null,
    seq: draftTemplate.value.length + 1,
  })
}

function removeParamRow(index: number) {
  draftTemplate.value.splice(index, 1)
}

const PARAM_TYPES: ParamType[] = ['TEXT', 'NUMBER', 'BOOLEAN', 'SINGLE', 'DATETIME']

/**
 * 保存参数模板。
 *
 * `PUT /operator-versions/{id}` 收的是**整包 meta**（未提交的字段会被清空），
 * 所以这里必须把现有的其它字段一起带上 —— 只发 paramTemplate 会把启动命令抹掉。
 */
async function submitTemplate() {
  const v = version.value
  if (!v) return
  const keys = draftTemplate.value.map((p) => p.paramKey.trim())
  if (keys.some((k) => !k)) {
    ElMessage.warning('参数 key 不能为空')
    return
  }
  if (new Set(keys).size !== keys.length) {
    ElMessage.warning('参数 key 不能重复')
    return
  }
  saving.value = true
  try {
    await operatorVersionApi.updateMeta(versionId.value, {
      description: v.description ?? undefined,
      osType: v.osType,
      startCommand: v.startCommand ?? undefined,
      workDir: v.workDir ?? undefined,
      envVars: v.envVars ?? undefined,
      successCodes: v.successCodes ?? undefined,
      paramTemplate: draftTemplate.value.map((p, i) => ({
        ...p,
        tenantSeq: undefined,
        seq: i + 1,
      })) as ParamDef[],
      outputDeclarations: (v.outputDeclarations ?? []).map((o, i) => ({ ...o, seq: i + 1 })),
      defaultResource: v.defaultResource ?? undefined,
      defaultTimeoutSeconds: v.defaultTimeoutSeconds ?? undefined,
      defaultRetryCount: v.defaultRetryCount ?? undefined,
      defaultRetryIntervalSeconds: v.defaultRetryIntervalSeconds ?? undefined,
      logTailLines: v.logTailLines ?? undefined,
      logMaxBytes: v.logMaxBytes ?? undefined,
    })
    ElMessage.success('参数模板已保存')
    editVisible.value = false
    await load()
    resetParams()
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>
          {{ operatorName || '算子' }}
          <span class="ver">{{ version?.versionNo ?? '' }}</span>
        </h1>
        <div class="sub">
          <span class="mono">{{ versionId }}</span>
          <ToneChip v-if="version" :tone="pubTone(version.publishStatus)" :label="pubLabel(version.publishStatus)" />
          <ToneChip v-if="version?.isDefaultVersion" tone="mut" label="默认版本" />
        </div>
      </div>
      <div class="actions">
        <router-link :to="`/operators/${operatorId}`">
          <el-button>返回算子</el-button>
        </router-link>
      </div>
    </div>

    <!-- ── 配置信息 ─────────────────────────────────────── -->
    <div v-if="version" class="panel">
      <div class="kv">
        <div class="kv-item"><label>文件</label><span>{{ dash(version.fileName) }}</span></div>
        <div class="kv-item"><label>大小</label><span class="mono">{{ formatMb(version.fileSize) }}</span></div>
        <div class="kv-item"><label>SHA-256</label><span class="mono small">{{ dash(version.fileChecksum) }}</span></div>
        <div class="kv-item"><label>操作系统</label><span>{{ osLabel(version.osType) }}</span></div>
        <div class="kv-item"><label>成功码</label><span class="mono">{{ (version.successCodes ?? [0]).join(' / ') }}</span></div>
        <div class="kv-item"><label>默认超时</label><span class="mono">{{ version.defaultTimeoutSeconds ?? '—' }} 秒</span></div>
        <div class="kv-item"><label>默认重试</label><span class="mono">{{ version.defaultRetryCount ?? 0 }} 次</span></div>
        <div class="kv-item"><label>发布人</label><span>{{ dash(version.publisher) }}</span></div>
        <div class="kv-item"><label>发布时间</label><span class="mono">{{ version.publishedAt ? formatDateTime(version.publishedAt) : '未发布' }}</span></div>
      </div>
      <div class="cmd-line">
        <label>启动命令</label>
        <code>{{ dash(version.startCommand) }}</code>
      </div>
      <div v-if="version.workDir" class="cmd-line">
        <label>工作目录</label>
        <code>{{ version.workDir }}</code>
      </div>
      <div v-if="version.envVars?.length" class="cmd-line">
        <label>环境变量</label>
        <div class="envs">
          <span v-for="e in version.envVars" :key="e.key" class="env">
            <b>{{ e.key }}</b>=<i>{{ e.secret ? '***' : e.value }}</i>
          </span>
        </div>
      </div>
    </div>

    <!-- ── 试运行 ───────────────────────────────────────── -->
    <div class="panel">
      <div class="panel-head">
        <h2>试运行</h2>
        <span class="sub">
          单次上限 {{ 600 }} 秒 · 不产生任务记录（PRD §10.6）· 命令与日志中的敏感值会被服务端打码
        </span>
        <div class="spacer" />
        <el-button
          v-if="can(PERM.OPERATOR_PARAM)"
          text
          size="small"
          :loading="savingDefaults"
          :disabled="running"
          @click="saveAsDefaults"
        >
          将本次参数另存为版本默认值
        </el-button>
      </div>

      <div class="run-grid">
        <div class="run-left">
          <el-form label-position="top">
            <el-row :gutter="12">
              <el-col :span="12">
                <el-form-item label="目标集群">
                  <el-select v-model="clusterId" style="width: 100%" placeholder="选择集群" @change="onClusterChange">
                    <el-option v-for="c in clusters" :key="c.clusterId" :label="c.clusterName" :value="c.clusterId" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="执行节点">
                  <el-select v-model="nodeId" style="width: 100%" placeholder="选择节点" :disabled="!clusterId">
                    <el-option
                      v-for="n in runnableNodes"
                      :key="n.executorNodeId"
                      :label="`${n.executorNodeName}（${n.ip}）`"
                      :value="n.executorNodeId"
                    />
                  </el-select>
                  <div v-if="clusterId && runnableNodes.length === 0" class="form-hint">
                    该集群下没有可用的 Linux 节点。试运行一期只支持 Linux（Q-02），且节点需已启用并绑定凭据。
                  </div>
                </el-form-item>
              </el-col>
            </el-row>

            <div v-if="paramTemplate.length" class="params">
              <div class="params-title">参数</div>
              <el-row :gutter="12">
                <el-col v-for="p in paramTemplate" :key="p.paramKey" :span="12">
                  <el-form-item>
                    <template #label>
                      <span class="param-label">
                        {{ p.name }}
                        <em v-if="p.required" class="req">*</em>
                        <span class="mono key">{{ p.paramKey }}</span>
                        <ToneChip v-if="p.sensitive" tone="warn" label="敏感" />
                        <ToneChip v-if="!p.runtimeOverridable" tone="mut" label="不可覆盖" />
                      </span>
                    </template>
                    <el-select
                      v-if="p.paramType === 'SINGLE'"
                      v-model="paramValues[p.paramKey]"
                      style="width: 100%"
                      clearable
                      :disabled="!p.runtimeOverridable"
                    >
                      <el-option v-for="opt in p.options ?? []" :key="opt" :label="opt" :value="opt" />
                    </el-select>
                    <el-switch
                      v-else-if="p.paramType === 'BOOLEAN'"
                      v-model="paramValues[p.paramKey]"
                      :disabled="!p.runtimeOverridable"
                    />
                    <el-input-number
                      v-else-if="p.paramType === 'NUMBER'"
                      v-model="paramValues[p.paramKey] as number"
                      style="width: 100%"
                      :disabled="!p.runtimeOverridable"
                    />
                    <el-date-picker
                      v-else-if="p.paramType === 'DATETIME'"
                      v-model="paramValues[p.paramKey] as string"
                      type="datetime"
                      value-format="YYYY-MM-DD HH:mm:ss"
                      style="width: 100%"
                      :disabled="!p.runtimeOverridable"
                    />
                    <el-input
                      v-else
                      v-model="paramValues[p.paramKey] as string"
                      :type="p.sensitive ? 'password' : 'text'"
                      :show-password="p.sensitive"
                      :placeholder="p.help ?? ''"
                      :disabled="!p.runtimeOverridable"
                    />
                    <div v-if="p.help" class="form-hint">{{ p.help }}</div>
                  </el-form-item>
                </el-col>
              </el-row>
            </div>
            <div v-else class="form-hint">
              该版本还没有参数模板。试运行仍可执行，但只能用启动命令里写死的内容
              <template v-if="isDraft">—— 建议先在「参数模板」页签里补上。</template>
            </div>

            <el-row :gutter="12">
              <el-col :span="8">
                <el-form-item label="超时（秒）">
                  <el-input-number v-model="timeoutSeconds" :min="1" :max="600" style="width: 100%" placeholder="默认 600" />
                </el-form-item>
              </el-col>
            </el-row>

            <div class="run-actions">
              <el-button type="primary" :loading="running" @click="runDryRun">
                {{ running ? '执行中…' : '开始试运行' }}
              </el-button>
              <el-button v-if="running" @click="stop">中止</el-button>
              <span class="sub">试运行会在目标节点上真实执行该命令（不是模拟）</span>
            </div>
          </el-form>
        </div>

        <div class="run-right">
          <div v-if="command" class="cmd-card">
            <div class="cmd-head">
              <b>{{ command.nodeName }}</b>
              <span class="mono">{{ command.machineIp }}</span>
              <span class="mono">超时 {{ command.timeoutSeconds }}s</span>
              <span class="mono">成功码 {{ (command.successCodes ?? []).join('/') }}</span>
            </div>
            <pre class="cmd-body">{{ command.command }}</pre>
            <div v-if="Object.keys(command.sources ?? {}).length" class="sources">
              <span v-for="(layer, key) in command.sources" :key="key" class="src">
                <b class="mono">{{ key }}</b> ← {{ layer }}
              </span>
            </div>
          </div>

          <div class="log-wrap">
            <div class="log-head">
              <span>实时日志</span>
              <span class="mono">{{ lines.length }} 行</span>
              <span v-if="running" class="live">● 运行中</span>
            </div>
            <div ref="logBox" class="log-box">
              <div v-for="l in lines" :key="l.seq" class="log-line" :class="{ err: l.stream !== 'stdout' }">
                <span class="mono seq">{{ l.seq }}</span>
                <span class="content">{{ l.content }}</span>
              </div>
              <div v-if="lines.length === 0" class="log-empty">
                {{ running ? '等待输出…' : '还没有日志。选择节点并点「开始试运行」' }}
              </div>
            </div>
          </div>

          <div v-if="eof" class="result" :class="{ ok: eof.success, fail: !eof.success }">
            <div class="result-head">
              <ToneChip :tone="eof.success ? 'ok' : 'fail'" :label="eof.success ? '试运行成功' : '试运行失败'" />
              <span class="mono">退出码 {{ exitCodeText }}</span>
              <span class="mono">耗时 {{ eof.durationMs ?? '—' }} ms</span>
            </div>
            <div class="result-hint">{{ eof.failReason || exitHint }}</div>
          </div>

          <div v-if="dryRunError" class="result fail">
            <div class="result-head">
              <ToneChip tone="fail" :label="streamStarted ? '执行出错' : '未能开始'" />
              <span class="mono">code={{ dryRunError.code }}</span>
            </div>
            <div class="result-hint">{{ dryRunError.message }}</div>
            <div v-if="!streamStarted" class="result-hint">
              这是**建流前**的校验结果，参数或节点选择需要调整后重试。
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ── 参数模板 / 输出声明 / 引用 ────────────────────── -->
    <div class="panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane name="params">
          <template #label>参数模板（{{ paramTemplate.length }}）</template>
          <div class="tab-actions">
            <el-button v-if="isDraft && can(PERM.OPERATOR_PUBLISH)" size="small" @click="openEdit">
              编辑参数模板
            </el-button>
            <span v-if="!isDraft" class="sub">已发布/已下线的版本不可编辑（D-11）—— 需要改就上传新版本</span>
          </div>
          <table class="tbl">
            <thead>
              <tr>
                <th>序</th>
                <th>参数 key</th>
                <th>显示名</th>
                <th>类型</th>
                <th>必填</th>
                <th>默认值</th>
                <th>可覆盖</th>
                <th>敏感</th>
                <th>候选值</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in paramTemplate" :key="p.paramKey">
                <td class="mono">{{ p.seq }}</td>
                <td class="mono">{{ p.paramKey }}</td>
                <td>{{ p.name }}</td>
                <td>{{ paramTypeLabel(p.paramType) }}</td>
                <td>{{ p.required ? '是' : '否' }}</td>
                <td class="mono">{{ p.sensitive ? '***' : dash(p.defaultValue) }}</td>
                <td>{{ p.runtimeOverridable ? '是' : '否' }}</td>
                <td>{{ p.sensitive ? '是' : '否' }}</td>
                <td class="mono small">{{ p.options?.join(', ') ?? '—' }}</td>
              </tr>
              <tr v-if="paramTemplate.length === 0">
                <td colspan="9" class="empty-hint">暂无参数</td>
              </tr>
            </tbody>
          </table>
        </el-tab-pane>

        <el-tab-pane name="outputs">
          <template #label>输出声明（{{ version?.outputDeclarations?.length ?? 0 }}）</template>
          <table class="tbl">
            <thead>
              <tr>
                <th>序</th>
                <th>变量名</th>
                <th>提取方式</th>
                <th>表达式</th>
                <th>类型</th>
                <th>示例</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="o in version?.outputDeclarations ?? []" :key="o.varName">
                <td class="mono">{{ o.seq }}</td>
                <td class="mono">{{ o.varName }}</td>
                <td>{{ extractLabel(o.extractMode) }}</td>
                <td class="mono small">{{ dash(o.expression) }}</td>
                <td>{{ dash(o.valueType) }}</td>
                <td class="mono">{{ dash(o.exampleValue) }}</td>
                <td>{{ dash(o.description) }}</td>
              </tr>
              <tr v-if="!version?.outputDeclarations?.length">
                <td colspan="7" class="empty-hint">
                  暂无输出声明 —— 下游步骤无法用 <code>${step.X.output.varName}</code> 引用本步骤产出
                </td>
              </tr>
            </tbody>
          </table>
        </el-tab-pane>

        <el-tab-pane name="refs">
          <template #label>引用（{{ references.length }}）</template>
          <table class="tbl">
            <thead>
              <tr>
                <th>工作流</th>
                <th>步骤</th>
                <th>该工作流版本</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in references" :key="`${r.workflowId}-${r.stepName}`">
                <td>
                  <router-link class="link-id" :to="`/workflows/${r.workflowId}`">{{ r.workflowName }}</router-link>
                  <div class="sub mono">{{ r.workflowId }}</div>
                </td>
                <td>{{ r.stepName }}</td>
                <td class="mono">{{ r.versionNo }}</td>
              </tr>
              <tr v-if="references.length === 0">
                <td colspan="3" class="empty-hint">没有被任何工作流步骤引用（可以安全删除该算子）</td>
              </tr>
            </tbody>
          </table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- ── 参数模板编辑 ─────────────────────────────────── -->
    <el-dialog v-model="editVisible" title="编辑参数模板（仅草稿）" width="900px">
      <div class="form-hint" style="margin-bottom: 12px">
        参数模板描述「这个算子接受哪些输入」。写进启动命令的 <code>${paramKey}</code> 必须在这里有对应项，
        否则试运行会报「模板外参数」。
      </div>
      <table class="tbl">
        <thead>
          <tr>
            <th style="width: 130px">key *</th>
            <th style="width: 120px">显示名</th>
            <th style="width: 110px">类型</th>
            <th style="width: 60px">必填</th>
            <th>默认值</th>
            <th style="width: 60px">可覆盖</th>
            <th style="width: 60px">敏感</th>
            <th style="width: 130px">候选值（SINGLE）</th>
            <th style="width: 50px" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(p, i) in draftTemplate" :key="i">
            <td><el-input v-model="p.paramKey" size="small" /></td>
            <td><el-input v-model="p.name" size="small" /></td>
            <td>
              <el-select v-model="p.paramType" size="small">
                <el-option v-for="t in PARAM_TYPES" :key="t" :label="PARAM_TYPE_LABEL[t]" :value="t" />
              </el-select>
            </td>
            <td><el-checkbox v-model="p.required" /></td>
            <td><el-input v-model="p.defaultValue as string" size="small" /></td>
            <td><el-checkbox v-model="p.runtimeOverridable" /></td>
            <td><el-checkbox v-model="p.sensitive" /></td>
            <td>
              <el-select
                v-model="p.options"
                size="small"
                multiple
                filterable
                allow-create
                default-first-option
                :disabled="p.paramType !== 'SINGLE'"
                placeholder="回车添加"
              />
            </td>
            <td><el-button text size="small" @click="removeParamRow(i)">删</el-button></td>
          </tr>
        </tbody>
      </table>
      <el-button size="small" style="margin-top: 10px" @click="addParamRow">+ 添加参数</el-button>
      <div class="form-hint" style="margin-top: 12px">
        「可覆盖」关掉后，工作流步骤上不允许改这个参数（用于算子固有的输入路径之类）。
        「敏感」打开后，它的值在快照、命令回显与日志里都会被打码，且**不能**另存为版本默认值。
      </div>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitTemplate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ver {
  margin-left: 8px;
  font-size: 13px;
  color: var(--t3);
  font-weight: 400;
}

.kv {
  display: flex;
  flex-wrap: wrap;
  gap: 26px;
}

.kv-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.kv-item label {
  font-size: 11.5px;
  color: var(--t3);
}

.small {
  font-size: 11.5px;
}

.cmd-line {
  display: flex;
  gap: 12px;
  margin-top: 14px;
  align-items: baseline;
}

.cmd-line label {
  font-size: 11.5px;
  color: var(--t3);
  min-width: 62px;
}

.cmd-line code {
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
  padding: 4px 8px;
  font-size: 12px;
  word-break: break-all;
}

.envs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.env {
  font-size: 12px;
  font-family: var(--font-mono, monospace);
}

.env i {
  color: var(--t3);
}

.panel-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-head h2 {
  font-size: 15px;
  margin: 0;
}

.spacer {
  flex: 1;
}

.run-grid {
  display: grid;
  grid-template-columns: minmax(360px, 1fr) minmax(420px, 1.2fr);
  gap: 20px;
  align-items: start;
}

@media (max-width: 1180px) {
  .run-grid {
    grid-template-columns: 1fr;
  }
}

.params-title {
  font-size: 12px;
  color: var(--t3);
  margin: 4px 0 8px;
}

.param-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.req {
  color: var(--fail, #e5534b);
  font-style: normal;
}

.key {
  font-size: 11px;
  color: var(--t3);
}

.run-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 6px;
}

.cmd-card {
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
  padding: 10px 12px;
  margin-bottom: 12px;
}

.cmd-head {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 12px;
  color: var(--t2);
  margin-bottom: 8px;
}

.cmd-body {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--t1);
}

.sources {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 8px;
  font-size: 11px;
  color: var(--t3);
}

.log-wrap {
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.log-head {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 12px;
  color: var(--t3);
  padding: 8px 12px;
  background: var(--bg-inset);
  border-bottom: 1px solid var(--line-faint);
}

.live {
  color: var(--ok, #2ea043);
}

.log-box {
  height: 320px;
  overflow-y: auto;
  padding: 8px 12px;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  line-height: 1.6;
  background: var(--bg-1, transparent);
}

.log-line {
  display: flex;
  gap: 10px;
}

.log-line .seq {
  color: var(--t4);
  min-width: 34px;
  text-align: right;
  user-select: none;
}

.log-line .content {
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--t2);
}

.log-line.err .content {
  color: var(--fail, #e5534b);
}

.log-empty {
  color: var(--t3);
  padding: 20px 0;
  text-align: center;
}

.result {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  border: 1px solid var(--line-faint);
  background: var(--bg-inset);
}

.result.ok {
  border-color: var(--ok, #2ea043);
}

.result.fail {
  border-color: var(--fail, #e5534b);
}

.result-head {
  display: flex;
  gap: 14px;
  align-items: center;
  font-size: 12px;
  color: var(--t2);
}

.result-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--t3);
  line-height: 1.7;
}

.tab-actions {
  margin: 4px 0 10px;
}

.link-id {
  color: var(--t1);
  font-weight: 600;
  text-decoration: none;
}

.empty-hint {
  text-align: center;
  color: var(--t3);
  padding: 24px;
}

.form-hint {
  font-size: 11.5px;
  color: var(--t3);
  line-height: 1.7;
  padding: 8px 10px;
  margin-top: 6px;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
}

.form-hint code {
  color: var(--t1);
}
</style>
