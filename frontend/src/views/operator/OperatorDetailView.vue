<script setup lang="ts">
/**
 * 算子详情（PRD §10.6；原型 prototype/operator-detail.html）。
 *
 * 【页面职责】
 * ① 展示算子本体（名称/类型/归属项目/状态/描述）；
 * ② 列出它的**全部版本** —— 版本是"可执行内容"的真正载体，所以列表里直接给
 *    发布状态、默认版本标记、文件与校验和，以及发布/下线动作；
 * ③ 上传新版本（multipart：`file` + `meta` 的 JSON 字符串）。
 *
 * 【为什么上传要当前端自己拼 FormData】
 * 后端收的是 `file` + `meta`（**一段 JSON 字符串**）两个 part，不是扁平字段。
 * 拼装收在 `operatorVersionApi` 里一处（见 api/modules/operator.ts）。
 *
 * 【校验失败的呈现（42210）】
 * 后端把"文件问题"与"meta 问题"合并成一个 42210 + `errors[]`，每项带字段路径
 * （如 `paramTemplate[2].paramKey`）。所以这里不抢着做前端校验 —— 只做"必填的
 * 最低限度提醒"，真正的规则以后端为准，错误逐条回显。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { operatorApi, operatorVersionApi } from '@/api/modules/operator'
import type { BizError } from '@/api/http'
import type { OperatorItem, OperatorVersionItem, OperatorVersionMeta } from '@/api/types/operator'
import ToneChip from '@/components/biz/ToneChip.vue'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  OPERATOR_STATUS_LABEL,
  OPERATOR_STATUS_TONE,
  OPERATOR_TYPE_LABEL,
  OS_TYPE_LABEL,
  VERSION_PUBLISH_STATUS_LABEL,
  VERSION_PUBLISH_STATUS_TONE,
  type OperatorStatus,
  type OperatorType,
  type OsType,
  type Tone,
  type VersionPublishStatus,
} from '@/types/enums'
import { formatDateTime, formatMb } from '@/utils/format'

const route = useRoute()
const { can } = usePermission()

const operatorId = computed(() => String(route.params.operatorId))

const detail = ref<OperatorItem | null>(null)
const versions = ref<OperatorVersionItem[]>([])
const loading = ref(false)

const statusLabel = (s: string): string => OPERATOR_STATUS_LABEL[s as OperatorStatus] ?? s
const statusTone = (s: string): Tone => OPERATOR_STATUS_TONE[s as OperatorStatus] ?? 'idle'
const typeLabel = (t: string): string => OPERATOR_TYPE_LABEL[t as OperatorType] ?? t
const pubLabel = (s: string): string => VERSION_PUBLISH_STATUS_LABEL[s as VersionPublishStatus] ?? s
const pubTone = (s: string): Tone => VERSION_PUBLISH_STATUS_TONE[s as VersionPublishStatus] ?? 'idle'

async function load() {
  loading.value = true
  try {
    detail.value = await operatorApi.get(operatorId.value)
    versions.value = await operatorApi.listVersions(operatorId.value)
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ── 上传新版本 ─────────────────────────────────────────────
const uploadVisible = ref(false)
const uploading = ref(false)
const pickedFile = ref<File | null>(null)
const meta = ref<OperatorVersionMeta>({
  description: '',
  osType: 'LINUX',
  startCommand: '',
  workDir: '',
  successCodes: [0],
})

const OS_OPTIONS: OsType[] = ['LINUX', 'WINDOWS']

function openUpload() {
  pickedFile.value = null
  meta.value = { description: '', osType: 'LINUX', startCommand: '', workDir: '', successCodes: [0] }
  uploadVisible.value = true
}

function onFileChange(file: UploadFile) {
  pickedFile.value = file.raw ?? null
}

/** 退出码输入框用逗号分隔的字符串，提交前解析成数字数组。 */
const successCodesText = ref('0')
function parseCodes(): number[] | undefined {
  const codes = successCodesText.value
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => Number(s))
  return codes.length && codes.every((n) => Number.isInteger(n)) ? codes : undefined
}

async function submitUpload() {
  if (!pickedFile.value) {
    ElMessage.warning('请选择算子文件')
    return
  }
  if (!meta.value.startCommand?.trim()) {
    ElMessage.warning('启动命令必填（试运行与调度都用它）')
    return
  }
  const codes = parseCodes()
  if (!codes) {
    ElMessage.warning('退出码请填逗号分隔的整数，例如 0,2')
    return
  }
  uploading.value = true
  try {
    await operatorVersionApi.upload(operatorId.value, pickedFile.value, { ...meta.value, successCodes: codes })
    ElMessage.success('版本已上传（草稿）')
    uploadVisible.value = false
    load()
  } catch (e) {
    // 42210 的 errors[] 由拦截器 toast 一条摘要；这里把逐字段错误摊开给用户改表单
    const payload = (e as BizError).payload
    const errors = (payload?.errors ?? payload?.data) as
      | { field?: string; message?: string }[]
      | undefined
    if ((e as BizError).code === 42210 && Array.isArray(errors) && errors.length) {
      const lines = errors.slice(0, 10).map((it) => `· ${it.field ?? ''}${it.message ?? ''}`)
      await ElMessageBox.alert(lines.join('\n'), '校验未通过', { type: 'warning' })
    }
  } finally {
    uploading.value = false
  }
}

// ── 发布 / 下线 ────────────────────────────────────────────
/** 「取消」在 Element Plus 里是 reject('cancel')，不是错误 —— 必须接住（见列表页同处注释）。 */
async function askConfirm(message: string, title: string, okText: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: okText, cancelButtonText: '取消' })
    return true
  } catch {
    return false
  }
}

async function publish(row: OperatorVersionItem) {
  const ok = await askConfirm(
    `发布 ${row.versionNo} 后，工作流步骤即可引用它；发布后文件与参数模板都不可再改（D-11）。确认发布？`,
    '发布版本',
    '发布',
  )
  if (!ok) return
  await operatorVersionApi.publish(row.versionId)
  ElMessage.success('已发布')
  load()
}

async function offline(row: OperatorVersionItem) {
  const ok = await askConfirm(
    `${row.versionNo} 下线后，新步骤不能再引用它（已引用它的历史版本不受影响）。确认下线？`,
    '下线版本',
    '下线',
  )
  if (!ok) return
  await operatorVersionApi.offline(row.versionId)
  ElMessage.success('已下线')
  load()
}
</script>

<template>
  <div v-loading="loading">
    <div class="page-header">
      <div>
        <h1>{{ detail?.operatorName ?? '算子详情' }}</h1>
        <div class="sub">
          <span class="mono">{{ operatorId }}</span>
          <span>{{ detail ? typeLabel(detail.operatorType) : '' }}</span>
          <span v-if="detail">归属 {{ detail.projectName }}</span>
        </div>
      </div>
      <div class="actions">
        <router-link to="/operators">
          <el-button>返回列表</el-button>
        </router-link>
        <el-button v-if="can(PERM.OPERATOR_PUBLISH)" type="primary" @click="openUpload">上传新版本</el-button>
      </div>
    </div>

    <div v-if="detail" class="panel">
      <div class="kv">
        <div class="kv-item">
          <label>状态</label>
          <ToneChip :tone="statusTone(detail.status)" :label="statusLabel(detail.status)" />
        </div>
        <div class="kv-item">
          <label>最新版本</label>
          <span class="mono">{{ detail.latestVersion ?? '—' }}</span>
        </div>
        <div class="kv-item">
          <label>版本数</label>
          <span class="mono">{{ detail.versionCount }}</span>
        </div>
        <div class="kv-item">
          <label>创建人</label>
          <span>{{ detail.creator }}</span>
        </div>
        <div class="kv-item">
          <label>创建时间</label>
          <span class="mono">{{ formatDateTime(detail.createdAt) }}</span>
        </div>
      </div>
      <div v-if="detail.description" class="desc">{{ detail.description }}</div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>版本列表</h2>
        <span class="sub">版本不可变：改内容只能上传新版本（D-11）</span>
      </div>
      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th>版本</th>
              <th>状态</th>
              <th>文件</th>
              <th>启动命令</th>
              <th>超时 / 重试</th>
              <th>发布人 / 时间</th>
              <th style="width: 220px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in versions" :key="row.versionId">
              <td>
                <router-link class="link-id" :to="`/operators/${operatorId}/versions/${row.versionId}`">
                  {{ row.versionNo }}
                </router-link>
                <div class="sub mono">{{ row.versionId }}</div>
              </td>
              <td>
                <ToneChip :tone="pubTone(row.publishStatus)" :label="pubLabel(row.publishStatus)" />
                <ToneChip v-if="row.isDefaultVersion" tone="mut" label="默认" />
              </td>
              <td>
                <div>{{ row.fileName ?? '—' }}</div>
                <div class="sub mono">{{ formatMb(row.fileSize) }}</div>
              </td>
              <td class="mono ellipsis" :title="row.startCommand ?? ''">{{ row.startCommand ?? '—' }}</td>
              <td class="mono">{{ row.defaultTimeoutSeconds ?? '—' }}s / {{ row.defaultRetryCount ?? 0 }} 次</td>
              <td>
                <div>{{ row.publisher ?? '—' }}</div>
                <div class="sub mono">{{ row.publishedAt ? formatDateTime(row.publishedAt) : '未发布' }}</div>
              </td>
              <td>
                <div class="actions">
                  <router-link :to="`/operators/${operatorId}/versions/${row.versionId}`">
                    <el-button text size="small">详情 / 试运行</el-button>
                  </router-link>
                  <el-button
                    v-if="can(PERM.OPERATOR_PUBLISH) && row.publishStatus === 'DRAFT'"
                    text
                    size="small"
                    @click="publish(row)"
                  >
                    发布
                  </el-button>
                  <el-button
                    v-if="can(PERM.OPERATOR_PUBLISH) && row.publishStatus === 'PUBLISHED'"
                    text
                    size="small"
                    @click="offline(row)"
                  >
                    下线
                  </el-button>
                </div>
              </td>
            </tr>
            <tr v-if="versions.length === 0">
              <td colspan="7" class="empty-hint">还没有版本，点右上角「上传新版本」开始</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <el-dialog v-model="uploadVisible" title="上传新版本" width="640px">
      <el-form label-position="top">
        <el-form-item label="算子文件" required>
          <el-upload :auto-upload="false" :limit="1" :on-change="onFileChange" :show-file-list="true">
            <el-button>选择文件</el-button>
          </el-upload>
          <div class="form-hint">
            服务端会计算 SHA-256 并按内容寻址落盘：同样的文件重复上传只会占用一份空间。
            默认上限 500MB。
          </div>
        </el-form-item>
        <el-form-item label="启动命令" required>
          <el-input v-model="meta.startCommand" placeholder="例如：python main.py --input ${inputPath}" />
          <div class="form-hint">
            可以用 <code>${paramKey}</code> 引用本版本的参数；命令里若能直接写死就不要用引用，更好排查。
          </div>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="操作系统">
              <el-select v-model="meta.osType" style="width: 100%">
                <el-option v-for="os in OS_OPTIONS" :key="os" :label="OS_TYPE_LABEL[os]" :value="os" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="成功退出码">
              <el-input v-model="successCodesText" placeholder="逗号分隔，例如 0,2" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="工作目录">
          <el-input v-model="meta.workDir" placeholder="例如：/opt/ops/etl" />
        </el-form-item>
        <el-form-item label="版本说明">
          <el-input v-model="meta.description" type="textarea" :rows="2" maxlength="2000" />
        </el-form-item>
        <div class="form-hint">
          参数模板与输出声明在版本详情页维护（那里有试运行面板，能立刻验证参数是否正确）。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传</el-button>
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

.kv {
  display: flex;
  flex-wrap: wrap;
  gap: 28px;
}

.kv-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.kv-item label {
  font-size: 11.5px;
  color: var(--t3);
}

.desc {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line-faint);
  color: var(--t2);
  line-height: 1.8;
  white-space: pre-wrap;
}

.panel-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
}

.panel-head h2 {
  font-size: 15px;
  margin: 0;
}

.ellipsis {
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  margin-top: 6px;
  background: var(--bg-inset);
  border: 1px solid var(--line-faint);
  border-radius: var(--r-sm);
}

.form-hint code {
  color: var(--t1);
}
</style>
