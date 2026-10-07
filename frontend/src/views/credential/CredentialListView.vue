<script setup lang="ts">
/**
 * 凭据管理（M2 实装；原型 prototype/credential-list.html，PRD §7.2 / docs/07 §6.2）。
 *
 * 【本页是"安全红线的前端一面"，三个机制值得说明】
 *
 * 1) 明文只在两个入口出现，且都不回显。
 *    创建/轮换弹窗的 secret 用 `type="password"` + `show-password`，提交后立刻置空局部变量；
 *    列表与详情只展示 `secretFingerprint`（后端已是 ****xxxx）。
 *    对应后端：CredentialVO 里根本没有 secret 字段（MapStruct unmappedTargetPolicy=ERROR
 *    保证"漏脱敏会编译失败"），前端的 CredentialItem 类型也照样没有 ——
 *    两侧都用类型系统而不是文档约定来兜底：**没写进去的东西不可能被误显示**。
 *
 * 2) 编辑与轮换是两个动作，不是一个表单里的可选字段。
 *    编辑（改名/改描述/改项目归属）**不带** secret；改密钥必须走「轮换」。
 *    原因：如果编辑表单里留着 secret 输入框，"改个名字顺手点了保存"就可能把密钥清掉或换掉。
 *    把"换密钥"做成独立意图，比在表单里加一句提示可靠。
 *    轮换后引用它的节点**无需重新绑定**（服务端换密文，引用关系不变，docs/07 §6.2）。
 *
 * 3) 删除按钮不做前置禁用。
 *    refCount 是**快照**（docs/05 §6.3：冗余计数绝不作业务判定唯一依据），
 *    真实是否可删由服务端实时 COUNT 决定 → 42202。
 *    前端拿快照去禁按钮，反而会出现"快照说 0、服务端说 2"的错配；
 *    所以这里让按钮永远可点，把权威判定留给服务端，错误码 42202 由全局拦截器 Toast。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { credentialApi } from '@/api/modules/credential'
import { projectApi } from '@/api/modules/project'
import type { CredentialItem, SaveCredentialParams } from '@/api/types/credential'
import type { ProjectItem } from '@/api/types/project'
import ToneChip from '@/components/biz/ToneChip.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  CREDENTIAL_STATUS_LABEL,
  CREDENTIAL_STATUS_TONE,
  CREDENTIAL_TYPE_LABEL,
  type CredentialStatus,
  type CredentialType,
  type Tone,
} from '@/types/enums'
import { dash, formatDateTime } from '@/utils/format'

const { can } = usePermission()

const { items, total, page, pageSize, keyword, loading, refresh } = useListQuery<CredentialItem>((params) =>
  credentialApi.page(params),
)

onMounted(refresh)

// ── 状态/类型查表（页面不写 if，F-5）──────────────────────────
const statusLabel = (s: string): string => CREDENTIAL_STATUS_LABEL[s as CredentialStatus] ?? s
const statusTone = (s: string): Tone => CREDENTIAL_STATUS_TONE[s as CredentialStatus] ?? 'idle'
const typeLabel = (t: string): string => CREDENTIAL_TYPE_LABEL[t as CredentialType] ?? t

// ── 分页（沿用原型轻量 pager，与集群列表视觉一致）──────────────
const totalPages = () => Math.max(1, Math.ceil(total.value / pageSize.value))
function go(target: number) {
  if (target < 1 || target > totalPages()) return
  page.value = target
  refresh()
}

// ── 项目下拉（供"项目级凭据"选择归属）──────────────────────
// 项目列表本身受 DataScope 过滤 —— 看不到的项目自然不在选项里，这一点与后端一致，
// 不需要前端再判一次权限（前端判定永远不是安全边界）。
const projects = ref<ProjectItem[]>([])
async function ensureProjects() {
  if (projects.value.length > 0) return
  const result = await projectApi.page({ page: 1, pageSize: 200 })
  projects.value = result.records
}

// ── 创建 / 编辑 ────────────────────────────────────────────
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)

interface CredentialForm {
  credentialName: string
  credentialType: CredentialType
  username: string
  /** 仅创建时上行；编辑时为空串（改密钥走「轮换」） */
  secret: string
  projectId: string | null
  expireAt: string | null
  description: string
}

const emptyForm = (): CredentialForm => ({
  credentialName: '',
  credentialType: 'SSH_KEY',
  username: '',
  secret: '',
  projectId: null,
  expireAt: null,
  description: '',
})

const form = ref<CredentialForm>(emptyForm())

async function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  await ensureProjects()
  dialogVisible.value = true
}

async function openEdit(item: CredentialItem) {
  editingId.value = item.credentialId
  form.value = {
    credentialName: item.credentialName,
    credentialType: (item.credentialType as CredentialType) ?? 'SSH_KEY',
    username: item.username ?? '',
    secret: '', // 编辑绝不预填、也不带 secret —— 换密钥是独立动作
    projectId: item.projectId,
    expireAt: item.expireAt,
    description: item.description ?? '',
  }
  await ensureProjects()
  dialogVisible.value = true
}

async function submitForm() {
  const isCreate = editingId.value === null
  if (!form.value.credentialName.trim()) {
    ElMessage.warning('凭据名称必填')
    return
  }
  if (isCreate && !form.value.secret.trim()) {
    ElMessage.warning('创建凭据必须填写密钥内容')
    return
  }
  saving.value = true
  try {
    // 显式组装 payload：编辑分支刻意不带 secret（类型上可选，用"不给"表达意图）
    const payload: SaveCredentialParams = {
      credentialName: form.value.credentialName,
      credentialType: form.value.credentialType,
      username: form.value.username || undefined,
      projectId: form.value.projectId ?? null,
      expireAt: form.value.expireAt ?? null,
      description: form.value.description || undefined,
    }
    if (isCreate) {
      payload.secret = form.value.secret
      await credentialApi.create(payload)
      ElMessage.success('凭据已创建（密钥已加密入库，此后不可回显）')
    } else {
      await credentialApi.update(editingId.value as string, payload)
      ElMessage.success('已保存')
    }
    form.value.secret = '' // 及时清掉本地明文副本
    dialogVisible.value = false
    refresh()
  } finally {
    saving.value = false
  }
}

// ── 轮换（独立动作 + 强提示）────────────────────────────────
const rotateVisible = ref(false)
const rotating = ref(false)
const rotateTarget = ref<CredentialItem | null>(null)
const rotateSecret = ref('')

function openRotate(item: CredentialItem) {
  rotateTarget.value = item
  rotateSecret.value = ''
  rotateVisible.value = true
}

async function submitRotate() {
  if (!rotateTarget.value) return
  if (!rotateSecret.value.trim()) {
    ElMessage.warning('新密钥内容必填')
    return
  }
  rotating.value = true
  try {
    await credentialApi.rotate(rotateTarget.value.credentialId, rotateSecret.value)
    ElMessage.success('密钥已轮换，引用该凭据的节点下次执行自动使用新密钥')
    rotateSecret.value = '' // 明文只在一次请求里存在
    rotateVisible.value = false
    refresh()
  } finally {
    rotating.value = false
  }
}

// ── 删除（权威判定在服务端：42202）──────────────────────────
async function remove(item: CredentialItem) {
  await ElMessageBox.confirm(
    `确认删除凭据「${item.credentialName}」？若它仍被执行节点引用，服务端会拒绝并给出引用数。`,
    '删除凭据',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
  )
  await credentialApi.remove(item.credentialId)
  ElMessage.success('已删除')
  refresh()
}
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>凭据管理</h1>
        <div class="sub">
          <span>AES-256-GCM 加密存储 · 明文永不出网 · 引用计数禁删（docs/07 §6.2）</span>
          <span class="mono">共 {{ total }} 条凭据</span>
        </div>
      </div>
      <div class="actions">
        <el-input
          v-model="keyword"
          placeholder="搜索凭据名"
          style="width: 200px"
          clearable
          @keyup.enter="go(1)"
        />
        <el-button @click="go(1)">查询</el-button>
        <el-button v-if="can(PERM.CREDENTIAL_WRITE)" type="primary" @click="openCreate">新建凭据</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="table-wrap" v-loading="loading">
        <table class="tbl">
          <thead>
            <tr>
              <th>凭据</th>
              <th>类型</th>
              <th>归属</th>
              <th>用户名</th>
              <th>密钥指纹</th>
              <th>引用</th>
              <th>状态</th>
              <th>最近轮换 / 过期</th>
              <th style="width: 200px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in items" :key="row.credentialId">
              <td>
                <div class="name">{{ row.credentialName }}</div>
                <div class="sub mono">{{ row.credentialId }}</div>
              </td>
              <td>{{ typeLabel(row.credentialType) }}</td>
              <td>
                <span v-if="row.projectId">{{ row.projectName ?? row.projectId }}</span>
                <ToneChip v-else tone="mut" label="平台级" />
              </td>
              <td class="mono">{{ dash(row.username) }}</td>
              <td class="mono" title="仅显示后 4 位；明文不可查看">{{ row.secretFingerprint }}</td>
              <td class="mono">{{ row.refCount }}</td>
              <td><ToneChip :tone="statusTone(row.status)" :label="statusLabel(row.status)" /></td>
              <td class="mono">
                <div>轮换 {{ formatDateTime(row.lastRotatedAt) }}</div>
                <div class="sub">过期 {{ formatDateTime(row.expireAt) }}</div>
              </td>
              <td>
                <div class="actions">
                  <el-button v-if="can(PERM.CREDENTIAL_WRITE)" text size="small" @click="openEdit(row)">
                    编辑
                  </el-button>
                  <el-button v-if="can(PERM.CREDENTIAL_ROTATE)" text size="small" @click="openRotate(row)">
                    轮换
                  </el-button>
                  <el-button v-if="can(PERM.CREDENTIAL_WRITE)" text size="small" @click="remove(row)">
                    删除
                  </el-button>
                </div>
              </td>
            </tr>
            <tr v-if="items.length === 0">
              <td colspan="9" class="empty-hint">{{ loading ? '加载中…' : '暂无凭据' }}</td>
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

    <!-- ── 创建 / 编辑弹窗 ───────────────────────────────── -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑凭据' : '新建凭据'"
      width="560px"
      @closed="form.secret = ''"
    >
      <el-form label-position="top">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="凭据名称" required>
              <el-input v-model="form.credentialName" maxlength="128" placeholder="例如：生产节点 SSH" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="凭据类型" required>
              <el-select v-model="form.credentialType" style="width: 100%">
                <el-option label="SSH 密钥" value="SSH_KEY" />
                <el-option label="用户名密码" value="USER_PASSWORD" />
                <el-option label="WinRM" value="WINRM" />
                <el-option label="Token" value="TOKEN" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="用户名">
              <el-input v-model="form.username" maxlength="64" placeholder="例如：flowops" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="归属项目">
              <el-select v-model="form.projectId" clearable placeholder="平台级（不归属项目）" style="width: 100%">
                <el-option
                  v-for="project in projects"
                  :key="project.projectId"
                  :label="project.projectName"
                  :value="project.projectId"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <!-- 编辑时不出 secret 输入框：换密钥是「轮换」这个独立动作 -->
        <el-form-item v-if="!editingId" label="密钥内容（创建后不可回显）" required>
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            :rows="4"
            placeholder="私钥全文 / 密码 / Token —— 提交即加密入库，此后任何接口都不返回它"
          />
        </el-form-item>
        <div v-else class="form-hint">
          编辑不会修改密钥内容。要更换密钥请用列表里的「轮换」——把"改名"和"换密码"分成两个意图，
          避免顺手保存时误改密钥。轮换后引用该凭据的节点无需重新绑定。
        </div>

        <el-form-item label="过期时间（留空 = 不过期）">
          <el-date-picker
            v-model="form.expireAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="选择过期时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- ── 轮换弹窗 ──────────────────────────────────────── -->
    <el-dialog v-model="rotateVisible" title="轮换密钥" width="520px" @closed="rotateSecret = ''">
      <div class="form-hint" style="margin-bottom: 12px">
        即将轮换「{{ rotateTarget?.credentialName }}」（{{ rotateTarget?.credentialId }}）。
        新密钥立即生效：引用该凭据的执行节点<b>无需重新绑定</b>，下次执行会用新密钥握手。
        不确定新密钥是否可用时，建议先在同一节点上做一次「连通性测试」。
      </div>
      <el-form label-position="top">
        <el-form-item label="新密钥内容" required>
          <el-input
            v-model="rotateSecret"
            type="password"
            show-password
            :rows="4"
            placeholder="私钥全文 / 新密码 / 新 Token"
          />
        </el-form-item>
        <div class="form-hint">
          提交后服务端重新加密并刷新指纹（列表里 ****xxxx 会变），明文不落日志、不进任何出参。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="rotateVisible = false">取消</el-button>
        <el-button type="primary" :loading="rotating" @click="submitRotate">确认轮换</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.name {
  color: var(--t1);
  font-weight: 600;
}

.sub {
  font-size: 11.5px;
  color: var(--t3);
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
  margin-bottom: 12px;
}
</style>
