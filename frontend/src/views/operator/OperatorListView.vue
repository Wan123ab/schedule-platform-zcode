<script setup lang="ts">
/**
 * 算子列表（PRD §10.6；原型 prototype/operator-list.html）。
 *
 * 【本页演示的机制】
 * - `useListQuery`：分页/关键词/loading/拉取这套状态机从页面抽走，页面只剩
 *   "筛选 + 渲染 + 动作"三件事（与集群列表同一范式，不是复制出来的另一套）。
 * - 状态与类型全部查 `types/enums.ts` 的表：页面里没有一处
 *   `if (operatorType === 'JAR')` 式的颜色/文案判定（F-5）。
 * - **42211 要单独处理**：删除算子被拒不是"操作失败"，而是"它正被工作流引用"——
 *   这两件事对用户的下一步动作完全不同（前者重试，后者去解绑）。拦截器只会给
 *   通用 toast，所以这里补一条带指引的提示。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { operatorApi } from '@/api/modules/operator'
import { projectApi } from '@/api/modules/project'
import type { BizError } from '@/api/http'
import type { OperatorItem, SaveOperatorParams } from '@/api/types/operator'
import type { ProjectItem } from '@/api/types/project'
import ToneChip from '@/components/biz/ToneChip.vue'
import { useListQuery } from '@/composables/useListQuery'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'
import {
  OPERATOR_STATUS_LABEL,
  OPERATOR_STATUS_TONE,
  OPERATOR_TYPE_LABEL,
  type OperatorStatus,
  type OperatorType,
  type Tone,
} from '@/types/enums'
import { formatDateTime } from '@/utils/format'

const { can } = usePermission()

const typeFilter = ref('')
const projectFilter = ref('')
const projects = ref<ProjectItem[]>([])

const { items, total, page, pageSize, keyword, loading, refresh } = useListQuery<OperatorItem>((params) =>
  operatorApi.page({
    ...params,
    operatorType: typeFilter.value || undefined,
    projectId: projectFilter.value || undefined,
  }),
)

onMounted(async () => {
  await refresh()
  // 项目下拉只取前 100 个：新建/筛选都够用，且不必为它单开一个"全量"端点
  projects.value = (await projectApi.page({ page: 1, pageSize: 100 })).records
})

// ── 查表（页面不做颜色判定）─────────────────────────────────
const statusLabel = (s: string): string => OPERATOR_STATUS_LABEL[s as OperatorStatus] ?? s
const statusTone = (s: string): Tone => OPERATOR_STATUS_TONE[s as OperatorStatus] ?? 'idle'
const typeLabel = (t: string): string => OPERATOR_TYPE_LABEL[t as OperatorType] ?? t

const OPERATOR_TYPE_OPTIONS: { value: OperatorType; label: string }[] = (
  Object.keys(OPERATOR_TYPE_LABEL) as OperatorType[]
).map((value) => ({ value, label: OPERATOR_TYPE_LABEL[value] }))

// ── 分页（与集群列表同款轻量 pager）─────────────────────────
const totalPages = () => Math.max(1, Math.ceil(total.value / pageSize.value))
function go(target: number) {
  if (target < 1 || target > totalPages()) return
  page.value = target
  refresh()
}

// ── 新建 / 编辑 ────────────────────────────────────────────
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const form = ref<SaveOperatorParams>({ operatorName: '', operatorType: 'JAR', projectId: '', description: '' })

function openCreate() {
  editingId.value = null
  form.value = { operatorName: '', operatorType: 'JAR', projectId: projectFilter.value || '', description: '' }
  dialogVisible.value = true
}

function openEdit(row: OperatorItem) {
  editingId.value = row.operatorId
  form.value = {
    operatorName: row.operatorName,
    operatorType: row.operatorType,
    projectId: row.projectId,
    description: row.description ?? '',
    status: row.status,
  }
  dialogVisible.value = true
}

async function submitForm() {
  if (!form.value.operatorName.trim()) {
    ElMessage.warning('算子名称必填')
    return
  }
  if (!form.value.projectId) {
    ElMessage.warning('请选择归属项目')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await operatorApi.update(editingId.value, form.value)
      ElMessage.success('已保存')
    } else {
      await operatorApi.create(form.value)
      ElMessage.success('算子已创建')
    }
    dialogVisible.value = false
    refresh()
  } finally {
    saving.value = false
  }
}

async function remove(row: OperatorItem) {
  try {
    await ElMessageBox.confirm(
      `确认删除算子「${row.operatorName}」？其下 ${row.versionCount} 个版本会一并删除。`,
      '删除算子',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    // 「取消」在 Element Plus 里是 reject('cancel')，不是错误：
    // 不接住它，@click 的 async 处理器会把 rejection 交给 Vue，控制台出现一条
    // 与用户操作无关的报错（本项目未配置 app.config.errorHandler）
    return
  }
  try {
    await operatorApi.remove(row.operatorId)
    ElMessage.success('已删除')
    refresh()
  } catch (e) {
    // 42211 = 引用闸门（docs/07 §5.3）：不是"重试就好"，而是"先去解绑"
    if ((e as BizError).code === 42211) {
      await ElMessageBox.alert(
        `「${row.operatorName}」下有版本正被工作流步骤引用，不能删除。\n` +
          '请先在对应工作流中解除引用（在算子版本详情的「引用」面板能看到是哪些工作流）。',
        '存在引用',
        { type: 'warning', confirmButtonText: '知道了' },
      )
    }
    // 其余错误已由 http 拦截器统一 toast，这里不重复提示
  }
}
</script>

<template>
  <div>
    <div class="page-header">
      <div>
        <h1>算子管理</h1>
        <div class="sub">
          <span>可被工作流编排的执行单元 · 每个算子下挂多个不可变版本（PRD §10.6）</span>
          <span class="mono">共 {{ total }} 个算子</span>
        </div>
      </div>
      <div class="actions">
        <el-select v-model="projectFilter" placeholder="全部项目" clearable style="width: 160px" @change="go(1)">
          <el-option v-for="p in projects" :key="p.projectId" :label="p.projectName" :value="p.projectId" />
        </el-select>
        <el-select v-model="typeFilter" placeholder="全部类型" clearable style="width: 130px" @change="go(1)">
          <el-option v-for="t in OPERATOR_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索算子名" style="width: 190px" clearable @keyup.enter="go(1)" />
        <el-button @click="go(1)">查询</el-button>
        <el-button v-if="can(PERM.OPERATOR_WRITE)" type="primary" @click="openCreate">新建算子</el-button>
      </div>
    </div>

    <div class="panel">
      <div class="table-wrap" v-loading="loading">
        <table class="tbl">
          <thead>
            <tr>
              <th>算子</th>
              <th>类型</th>
              <th>归属项目</th>
              <th>状态</th>
              <th>最新版本</th>
              <th>版本数</th>
              <th>创建人</th>
              <th>创建时间</th>
              <th style="width: 170px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in items" :key="row.operatorId">
              <td>
                <router-link class="link-id" :to="`/operators/${row.operatorId}`">{{ row.operatorName }}</router-link>
                <div class="sub mono">{{ row.operatorId }}</div>
              </td>
              <td><ToneChip tone="info" :label="typeLabel(row.operatorType)" /></td>
              <td>{{ row.projectName }}</td>
              <td><ToneChip :tone="statusTone(row.status)" :label="statusLabel(row.status)" /></td>
              <td class="mono">{{ row.latestVersion ?? '—' }}</td>
              <td class="mono">{{ row.versionCount }}</td>
              <td>{{ row.creator }}</td>
              <td class="mono">{{ formatDateTime(row.createdAt) }}</td>
              <td>
                <div class="actions">
                  <router-link :to="`/operators/${row.operatorId}`">
                    <el-button text size="small">详情</el-button>
                  </router-link>
                  <el-button v-if="can(PERM.OPERATOR_WRITE)" text size="small" @click="openEdit(row)">编辑</el-button>
                  <el-button v-if="can(PERM.OPERATOR_DELETE)" text size="small" @click="remove(row)">删除</el-button>
                </div>
              </td>
            </tr>
            <tr v-if="items.length === 0">
              <td colspan="9" class="empty-hint">{{ loading ? '加载中…' : '暂无算子' }}</td>
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

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑算子' : '新建算子'" width="560px">
      <el-form label-position="top">
        <el-form-item label="算子名称" required>
          <el-input v-model="form.operatorName" maxlength="128" placeholder="例如：数据清洗" />
        </el-form-item>
        <el-form-item label="算子类型">
          <el-select v-model="form.operatorType" style="width: 100%">
            <el-option v-for="t in OPERATOR_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属项目" required>
          <!-- 项目创建后不可变更：换项目等于把一份可能正被引用的资产搬出数据范围边界 -->
          <el-select v-model="form.projectId" style="width: 100%" :disabled="!!editingId" placeholder="请选择">
            <el-option v-for="p in projects" :key="p.projectId" :label="p.projectName" :value="p.projectId" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" />
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ENABLED">启用</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <div class="form-hint">
          算子本身只是「名称 + 类型 + 归属」的壳；真正的可执行内容（文件、启动命令、参数模板）都挂在<strong>版本</strong>上，
          且版本一经发布即不可修改（D-11）。
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
