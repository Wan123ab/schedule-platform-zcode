<script setup lang="ts">
/**
 * 项目空间列表（M2 第一页实装，PRD §10.2）。
 *
 * 【本页演示的机制】
 * - useRequest 模式的最简形态：ref + 手动 refresh（引入 useRequest composable 的时机是
 *   出现筛选竞态时，本页查询条件简单，不预支抽象）；
 * - ElDialog + 表单：创建/编辑走同一个弹窗（编辑时带 projectId）；
 * - 停用闸门的"友好化前置"（docs/07 §6.1）：先 impact 展示受影响清单，确认后才调
 *   updateStatus —— 服务端 42203 仍是最终防线（前端校验不是安全边界，docs/03 §4.3）；
 * - v-perm 等价物：写按钮用 usePermission().can 包裹（无权限直接不渲染，docs/04 §5.2）。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi } from '@/api/modules/project'
import type { ProjectItem, SaveProjectParams } from '@/api/types/project'
import { usePermission } from '@/composables/usePermission'
import { PERM } from '@/constants/permissions'

const { can } = usePermission()

const items = ref<ProjectItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const loading = ref(false)

async function refresh() {
  loading.value = true
  try {
    const result = await projectApi.page({ page: page.value, pageSize: pageSize.value, keyword: keyword.value })
    items.value = result.records
    total.value = result.total
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

// ── 创建 / 编辑弹窗 ──────────────────────────────────────────
const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const form = ref<SaveProjectParams>({ projectName: '', description: '' })

function openCreate() {
  editingId.value = null
  form.value = { projectName: '', description: '' }
  dialogVisible.value = true
}

function openEdit(item: ProjectItem) {
  editingId.value = item.projectId
  form.value = { projectName: item.projectName, description: item.description ?? '' }
  dialogVisible.value = true
}

async function submitForm() {
  if (!form.value.projectName.trim()) {
    ElMessage.warning('项目名称必填')
    return
  }
  if (editingId.value) {
    await projectApi.update(editingId.value, form.value)
    ElMessage.success('已保存')
  } else {
    await projectApi.create(form.value)
    ElMessage.success('项目已创建')
  }
  dialogVisible.value = false
  refresh()
}

// ── 停用/启用（闸门前置：impact → 确认 → 状态变更）────────────
async function toggleStatus(item: ProjectItem) {
  if (item.status === 'ENABLED') {
    const impact = await projectApi.impact(item.projectId)
    if (impact.runningTaskCount > 0) {
      // 服务端 42203 的前端预演：先给"受影响清单"，避免用户点了才失败
      ElMessageBox.alert(
        `该项目有 ${impact.runningTaskCount} 个运行中任务（如 ${impact.blocking.slice(0, 3).join('、')}），停用前需先等待其结束或手动停止。`,
        '无法停用',
        { confirmButtonText: '知道了' },
      )
      return
    }
    await ElMessageBox.confirm(
      `停用后项目内触发器将暂停（共 ${impact.triggerCount} 个），重新启用时自动恢复。确认停用「${item.projectName}」？`,
      '停用项目',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
    )
    await projectApi.updateStatus(item.projectId, 'DISABLED')
  } else {
    await projectApi.updateStatus(item.projectId, 'ENABLED')
  }
  ElMessage.success('状态已更新')
  refresh()
}
</script>

<template>
  <div>
    <div class="page-header">
      <h1>项目空间</h1>
      <div class="sub"><span>多租户隔离单元 · 所有工作流与任务的归属根（PRD §10.2）</span></div>
      <div class="actions">
        <el-input v-model="keyword" placeholder="搜索项目名" style="width: 220px" clearable @keyup.enter="refresh" />
        <el-button @click="refresh">查询</el-button>
        <el-button v-if="can(PERM.PROJECT_WRITE)" type="primary" @click="openCreate">新建项目</el-button>
      </div>
    </div>

    <div class="panel" v-loading="loading">
      <table class="ftable">
        <thead>
          <tr>
            <th>项目</th>
            <th>状态</th>
            <th class="mono">工作流 / 任务 / 成员</th>
            <th>负责人</th>
            <th class="mono">额度（并发/等待）</th>
            <th style="width: 180px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in items" :key="item.projectId">
            <td>
              <div class="proj-name">{{ item.projectName }}</div>
              <div class="mono proj-id">{{ item.projectId }}</div>
            </td>
            <td>
              <span class="st" :style="item.status === 'ENABLED'
                ? { color: 'var(--ok)', borderColor: 'var(--ok-line)', background: 'var(--ok-dim)' }
                : { color: 'var(--t3)', borderColor: 'var(--line)', background: 'transparent' }">
                {{ item.status === 'ENABLED' ? '● 启用' : '■ 停用' }}
              </span>
            </td>
            <td class="mono">{{ item.statWorkflowCount }} / {{ item.statTaskCount }} / {{ item.statMemberCount }}</td>
            <td>{{ item.ownerUsername ?? '—' }}</td>
            <td class="mono">{{ item.maxConcurrentTasks }} / {{ item.maxWaitingTasks }}</td>
            <td>
              <el-button v-if="can(PERM.PROJECT_WRITE)" text size="small" @click="openEdit(item)">编辑</el-button>
              <el-button v-if="can(PERM.PROJECT_WRITE)" text size="small" @click="toggleStatus(item)">
                {{ item.status === 'ENABLED' ? '停用' : '启用' }}
              </el-button>
            </td>
          </tr>
          <tr v-if="items.length === 0">
            <td colspan="6" class="empty-hint">{{ loading ? '加载中…' : '暂无项目' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑项目' : '新建项目'" width="480px">
      <el-form label-position="top">
        <el-form-item label="项目名称" required>
          <el-input v-model="form.projectName" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
  align-items: center;
}

.ftable {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}

.ftable th,
.ftable td {
  text-align: left;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line-faint);
}

.ftable th {
  color: var(--t3);
  font-weight: 500;
  font-size: 11.5px;
}

.ftable tbody tr:hover {
  background: var(--bg-hover);
}

.mono {
  font-family: var(--mono);
  font-size: 11.5px;
  color: var(--t2);
}

.proj-name {
  color: var(--t1);
  font-weight: 600;
}

.proj-id {
  margin-top: 2px;
}

.st {
  display: inline-block;
  padding: 2px 9px;
  border: 1px solid;
  border-radius: 999px;
  font-size: 11.5px;
}

.empty-hint {
  text-align: center;
  color: var(--t3);
  padding: 30px;
}
</style>
