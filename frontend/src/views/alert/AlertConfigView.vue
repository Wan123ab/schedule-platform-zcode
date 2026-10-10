<script setup lang="ts">
/**
 * 告警配置（M4-S4 实装；原型 prototype/alert-config.html，PRD §10.12）。
 *
 * 【三个 tab】规则（CRUD + 启停）/ 渠道（4 类型 upsert + 真发测试）/ 告警记录（history）。
 *
 * 【口径纪律】
 * - 事件类型 9 值以 DDL CHECK + V5 seed 为准（CONTRACT §9 只列 5 个是过时口径，
 *   README-M4 勘定；types/alert.ts 的 ALERT_EVENT_TYPES 是前端单一来源）；
 * - 渠道 secret 只写不读（后端加密落库、VO 永不回显），编辑时留空 = 不修改；
 * - EMAIL 一期占位不真实投递（O-65），测试结果如实展示给用户。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alertApi } from '@/api/modules/alert'
import type {
  AlertChannel,
  AlertRecord,
  AlertRule,
  AlertRuleUpsertParams,
} from '@/api/types/alert'
import {
  ALERT_CHANNEL_TYPES,
  ALERT_CHANNEL_TYPE_LABEL,
  ALERT_EVENT_TYPES,
  ALERT_EVENT_TYPE_LABEL,
  ALERT_RESULT_LABEL,
  type AlertChannelType,
  type AlertEventType,
} from '@/api/types/alert'
import ToneChip from '@/components/biz/ToneChip.vue'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  ALERT_LEVEL_TONE,
  ALERT_RESULT_TONE,
  type AlertLevel,
  type AlertResult,
  type Tone,
} from '@/types/enums'
import { ALERT_LEVEL_LABEL } from '@/api/types/alert'
import { formatDateTime } from '@/utils/format'

const { can } = usePermission()
const canWrite = computed(() => can(PERM.ALERT_WRITE))
const activeTab = ref('rules')

// ── 规则 ─────────────────────────────────────────────────
const rules = ref<AlertRule[]>([])
const rulesLoading = ref(false)

async function loadRules() {
  rulesLoading.value = true
  try {
    rules.value = await alertApi.listRules()
  } finally {
    rulesLoading.value = false
  }
}
onMounted(loadRules)

const ruleDialogVisible = ref(false)
const editingRuleId = ref<string | null>(null)
const ruleSaving = ref(false)
const ruleForm = ref<AlertRuleUpsertParams>({
  ruleName: '',
  eventType: 'TASK_FAILED',
  channels: [],
  receivers: [],
  suppressWindowMinutes: 30,
})

function openCreateRule() {
  editingRuleId.value = null
  ruleForm.value = {
    ruleName: '',
    eventType: 'TASK_FAILED',
    channels: [],
    receivers: [],
    suppressWindowMinutes: 30,
  }
  ruleDialogVisible.value = true
}

function openEditRule(rule: AlertRule) {
  editingRuleId.value = rule.ruleId
  ruleForm.value = {
    ruleName: rule.ruleName,
    eventType: rule.eventType,
    channels: [...rule.channels],
    receivers: [...rule.receivers],
    suppressWindowMinutes: rule.suppressWindowMinutes,
  }
  ruleDialogVisible.value = true
}

async function submitRule() {
  if (!ruleForm.value.ruleName.trim() || ruleForm.value.channels.length === 0) {
    ElMessage.warning('规则名称与至少一个通知渠道必填')
    return
  }
  if (ruleForm.value.suppressWindowMinutes < 1) {
    ElMessage.warning('抑制窗口至少 1 分钟')
    return
  }
  ruleSaving.value = true
  try {
    if (editingRuleId.value) {
      await alertApi.updateRule(editingRuleId.value, ruleForm.value)
      ElMessage.success('规则已保存')
    } else {
      await alertApi.createRule(ruleForm.value)
      ElMessage.success('规则已创建')
    }
    ruleDialogVisible.value = false
    loadRules()
  } finally {
    ruleSaving.value = false
  }
}

async function toggleRule(rule: AlertRule) {
  await alertApi.updateRuleEnabled(rule.ruleId, !rule.enabled)
  ElMessage.success(rule.enabled ? '已停用' : '已启用')
  loadRules()
}

async function removeRule(rule: AlertRule) {
  await ElMessageBox.confirm(`确认删除告警规则「${rule.ruleName}」？删除后不可恢复。`, '删除规则', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消',
  })
  await alertApi.removeRule(rule.ruleId)
  ElMessage.success('已删除')
  loadRules()
}

// ── 渠道 ─────────────────────────────────────────────────
const channels = ref<AlertChannel[]>([])
const channelsLoading = ref(false)
const testing = ref<AlertChannelType | null>(null)

async function loadChannels() {
  channelsLoading.value = true
  try {
    channels.value = await alertApi.listChannels()
  } finally {
    channelsLoading.value = false
  }
}
onMounted(loadChannels)

const channelDialogVisible = ref(false)
const editingType = ref<AlertChannelType>('WEBHOOK')
const channelSaving = ref(false)
const channelForm = ref({ endpoint: '', secret: '' })

function openChannel(type: AlertChannelType) {
  editingType.value = type
  const existing = channels.value.find((c) => c.channelType === type)
  // secret 不回显：留空 = 不修改（后端只写不读）
  channelForm.value = { endpoint: existing?.endpoint ?? '', secret: '' }
  channelDialogVisible.value = true
}

async function submitChannel() {
  if (!channelForm.value.endpoint.trim()) {
    ElMessage.warning('端点地址必填')
    return
  }
  channelSaving.value = true
  try {
    const params: { endpoint: string; secret?: string } = { endpoint: channelForm.value.endpoint.trim() }
    if (channelForm.value.secret.trim()) params.secret = channelForm.value.secret.trim()
    await alertApi.updateChannel(editingType.value, params)
    ElMessage.success('渠道已保存')
    channelDialogVisible.value = false
    loadChannels()
  } finally {
    channelSaving.value = false
  }
}

async function testChannel(type: AlertChannelType) {
  testing.value = type
  try {
    const result = await alertApi.testChannel(type)
    if (result.ok) {
      ElMessage.success(`测试消息已送达（${result.latencyMs}ms）`)
    } else {
      ElMessage.error(`测试失败：${result.error ?? '未知原因'}`)
    }
    loadChannels()
  } finally {
    testing.value = null
  }
}

// ── 告警记录 ─────────────────────────────────────────────
const records = ref<AlertRecord[]>([])
const recordsTotal = ref(0)
const recordsPage = ref(1)
const recordsLoading = ref(false)
const recordEventFilter = ref('')

async function loadRecords() {
  recordsLoading.value = true
  try {
    const result = await alertApi.history({
      eventType: recordEventFilter.value || undefined,
      page: recordsPage.value,
      pageSize: 20,
    })
    records.value = result.records
    recordsTotal.value = result.total
  } finally {
    recordsLoading.value = false
  }
}
onMounted(loadRecords)

const recordsTotalPages = () => Math.max(1, Math.ceil(recordsTotal.value / 20))
function goRecords(target: number) {
  if (target < 1 || target > recordsTotalPages()) return
  recordsPage.value = target
  loadRecords()
}

const levelLabel = (l: string): string => ALERT_LEVEL_LABEL[l] ?? l
const levelTone = (l: string): Tone => ALERT_LEVEL_TONE[l as AlertLevel] ?? 'idle'
const resultLabel = (r: string): string => ALERT_RESULT_LABEL[r] ?? r
const resultTone = (r: string): Tone => ALERT_RESULT_TONE[r as AlertResult] ?? 'idle'
const eventLabel = (e: string): string =>
  ALERT_EVENT_TYPE_LABEL[e as AlertEventType] ?? e

/** 切 tab 时拉对应数据（三个 tab 各自独立，首次进入已加载） */
function onTabChange(tab: string | number) {
  if (tab === 'rules') loadRules()
  else if (tab === 'channels') loadChannels()
  else if (tab === 'records') loadRecords()
}
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>告警配置</h1>
        <div class="sub">
          <span>事件规则 · 通知渠道 · 发送记录（PRD §10.12）</span>
        </div>
      </div>
    </div>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- ── 规则 ── -->
      <el-tab-pane label="告警规则" name="rules">
        <div class="panel">
          <div class="toolbar">
            <span class="sub">同 event+target 在抑制窗口内只发一次（防刷屏）</span>
            <el-button v-if="canWrite" type="primary" size="small" @click="openCreateRule">新建规则</el-button>
          </div>
          <div class="table-wrap" v-loading="rulesLoading">
            <table class="tbl">
              <thead>
                <tr>
                  <th>规则</th>
                  <th>事件类型</th>
                  <th>通知渠道</th>
                  <th>抑制窗口</th>
                  <th>生效范围</th>
                  <th>状态</th>
                  <th style="width: 180px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in rules" :key="r.ruleId">
                  <td>
                    <div class="link-id">{{ r.ruleName }}</div>
                    <div class="sub mono">{{ r.ruleId }}</div>
                  </td>
                  <td>{{ eventLabel(r.eventType) }}</td>
                  <td class="mono">{{ r.channels.join('、') }}</td>
                  <td class="mono">{{ r.suppressWindowMinutes }} 分钟</td>
                  <td>{{ r.projectId ?? r.clusterId ?? '全局' }}</td>
                  <td><ToneChip :tone="r.enabled ? 'ok' : 'mut'" :label="r.enabled ? '已启用' : '已停用'" /></td>
                  <td>
                    <div class="actions" v-if="canWrite">
                      <el-button text size="small" @click="openEditRule(r)">编辑</el-button>
                      <el-button text size="small" @click="toggleRule(r)">
                        {{ r.enabled ? '停用' : '启用' }}
                      </el-button>
                      <el-button text size="small" @click="removeRule(r)">删除</el-button>
                    </div>
                  </td>
                </tr>
                <tr v-if="rules.length === 0">
                  <td colspan="7" class="empty-hint">{{ rulesLoading ? '加载中…' : '暂无规则（V5 seed 会预置默认规则）' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </el-tab-pane>

      <!-- ── 渠道 ── -->
      <el-tab-pane label="通知渠道" name="channels">
        <div class="panel">
          <div class="table-wrap" v-loading="channelsLoading">
            <table class="tbl">
              <thead>
                <tr>
                  <th>渠道</th>
                  <th>端点</th>
                  <th>验证状态</th>
                  <th>最近测试</th>
                  <th style="width: 220px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="type in ALERT_CHANNEL_TYPES" :key="type">
                  <td class="link-id">{{ ALERT_CHANNEL_TYPE_LABEL[type] }}</td>
                  <td class="mono ch-endpoint">
                    {{ channels.find((c) => c.channelType === type)?.endpoint ?? '未配置' }}
                  </td>
                  <td>
                    <ToneChip
                      :tone="channels.find((c) => c.channelType === type)?.verified ? 'ok' : 'warn'"
                      :label="channels.find((c) => c.channelType === type)?.verified ? '已验证' : '未验证'"
                    />
                  </td>
                  <td>
                    <template v-if="channels.find((c) => c.channelType === type)?.lastTestAt">
                      <span class="mono">{{ formatDateTime(channels.find((c) => c.channelType === type)?.lastTestAt) }}</span>
                      <div class="sub">{{ channels.find((c) => c.channelType === type)?.lastTestResult }}</div>
                    </template>
                    <template v-else>—</template>
                  </td>
                  <td>
                    <div class="actions" v-if="canWrite">
                      <el-button text size="small" @click="openChannel(type)">配置</el-button>
                      <el-button
                        text
                        size="small"
                        :loading="testing === type"
                        :disabled="!channels.find((c) => c.channelType === type)?.endpoint"
                        @click="testChannel(type)"
                      >
                        发送测试
                      </el-button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="hint">密钥只写不读（加密落库）；发送测试会真实投递一条测试消息并自动标记"已验证"。邮件渠道一期未接入真实投递。</div>
        </div>
      </el-tab-pane>

      <!-- ── 记录 ── -->
      <el-tab-pane label="告警记录" name="records">
        <div class="panel">
          <div class="toolbar">
            <el-select
              v-model="recordEventFilter"
              placeholder="全部事件类型"
              clearable
              style="width: 180px"
              @change="goRecords(1)"
            >
              <el-option v-for="t in ALERT_EVENT_TYPES" :key="t" :label="eventLabel(t)" :value="t" />
            </el-select>
            <el-button @click="goRecords(1)">查询</el-button>
            <span class="sub mono" style="margin-left: auto">共 {{ recordsTotal }} 条</span>
          </div>
          <div class="table-wrap" v-loading="recordsLoading">
            <table class="tbl">
              <thead>
                <tr>
                  <th>告警</th>
                  <th>级别</th>
                  <th>事件类型</th>
                  <th>对象</th>
                  <th>投递结果</th>
                  <th>触发时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="rec in records" :key="rec.alertId">
                  <td>
                    <div class="link-id">{{ rec.content }}</div>
                    <div class="sub mono">{{ rec.alertId }}</div>
                  </td>
                  <td><ToneChip :tone="levelTone(rec.level)" :label="levelLabel(rec.level)" /></td>
                  <td>{{ eventLabel(rec.eventType) }}</td>
                  <td>
                    <div>{{ rec.targetName }}</div>
                    <div class="sub mono">{{ rec.targetType }}</div>
                  </td>
                  <td>
                    <ToneChip :tone="resultTone(rec.result)" :label="resultLabel(rec.result)" />
                    <div class="sub mono">{{ rec.channels.join('、') }}</div>
                  </td>
                  <td class="mono">{{ formatDateTime(rec.firedAt) }}</td>
                </tr>
                <tr v-if="records.length === 0">
                  <td colspan="6" class="empty-hint">{{ recordsLoading ? '加载中…' : '暂无告警记录' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="pager">
            <span>第 {{ recordsPage }} / {{ recordsTotalPages() }} 页</span>
            <div class="pgs">
              <button :disabled="recordsPage <= 1" @click="goRecords(recordsPage - 1)">‹</button>
              <button class="on">{{ recordsPage }}</button>
              <button :disabled="recordsPage >= recordsTotalPages()" @click="goRecords(recordsPage + 1)">›</button>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 规则弹窗 -->
    <el-dialog v-model="ruleDialogVisible" :title="editingRuleId ? '编辑告警规则' : '新建告警规则'" width="560px">
      <el-form label-position="top">
        <el-form-item label="规则名称" required>
          <el-input v-model="ruleForm.ruleName" maxlength="128" placeholder="例如：任务失败告警" />
        </el-form-item>
        <el-form-item label="事件类型" required>
          <el-select v-model="ruleForm.eventType" style="width: 100%">
            <el-option v-for="t in ALERT_EVENT_TYPES" :key="t" :label="eventLabel(t)" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="通知渠道（至少一个）" required>
          <el-checkbox-group v-model="ruleForm.channels">
            <el-checkbox v-for="t in ALERT_CHANNEL_TYPES" :key="t" :value="t">
              {{ ALERT_CHANNEL_TYPE_LABEL[t as AlertChannelType] }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="接收人（可选，多个用回车分隔）">
          <el-select
            v-model="ruleForm.receivers"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入用户名后回车"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="抑制窗口（分钟，同 event+target 窗口内只发一次）">
          <el-input-number v-model="ruleForm.suppressWindowMinutes" :min="1" :step="5" style="width: 160px" />
        </el-form-item>
        <div class="hint">生效范围一期为全局（scope 展平口径见 README-M4 O-66）；条件 JSON 暂不开放编辑。</div>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="submitRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 渠道弹窗 -->
    <el-dialog v-model="channelDialogVisible" :title="`配置渠道：${ALERT_CHANNEL_TYPE_LABEL[editingType]}`" width="520px">
      <el-form label-position="top">
        <el-form-item label="端点地址" required>
          <el-input
            v-model="channelForm.endpoint"
            :placeholder="editingType === 'EMAIL' ? '接收邮箱（一期占位）' : 'https://...'"
          />
        </el-form-item>
        <el-form-item label="密钥（可选，只写不读；留空 = 不修改）">
          <el-input v-model="channelForm.secret" type="password" show-password placeholder="Webhook 加签密钥等" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="channelDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="channelSaving" @click="submitChannel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.link-id {
  color: var(--t1);
  font-weight: 600;
}

.empty-hint {
  text-align: center;
  color: var(--t3);
  padding: 30px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
}

.hint {
  font-size: 11.5px;
  color: var(--t3);
  line-height: 1.7;
  padding: 8px 14px;
  border-top: 1px solid var(--line-faint);
}

.ch-endpoint {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
