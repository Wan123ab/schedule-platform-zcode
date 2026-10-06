<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api/modules/auth'
import { useAuthStore } from '@/stores/auth'
import type { BizError } from '@/api/http'

/** 登录页（原型 login.html 的工程实现，M0 联调闭环：POST /auth/login → ApiResult）。 */
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  loading.value = true
  try {
    const result = await authApi.login({ ...form })
    auth.setSession({
      token: result.tokenValue,
      userId: result.user.userId,
      username: result.user.username,
      displayName: result.user.displayName,
      roles: result.user.roles,
      scopeTypes: result.user.scopeTypes,
      permissions: result.permissions,
    })
    ElMessage.success(`欢迎，${result.user.displayName}`)
    router.push((route.query.redirect as string) || '/dashboard')
  } catch (e) {
    const err = e as BizError
    ElMessage.error(`登录失败（${err.code ?? '网络错误'}）：${err.message ?? ''}`)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-card panel">
      <div class="login-brand">
        <span class="brand-mark">◆</span>
        <div>
          <div class="brand-name" style="font-size: 17px">FlowOps</div>
          <div class="brand-sub">智能工作流调度与监控平台</div>
        </div>
      </div>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="admin" autofocus />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="••••••••" @keyup.enter="submit" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" native-type="submit" @click="submit">
          登 录
        </el-button>
      </el-form>

      <p class="login-hint">M0 骨架联调环境 · 种子账号 admin / Admin@123</p>
    </div>
  </div>
</template>

<style scoped>
.login-wrap {
  position: relative;
  z-index: 1;
  width: 100%;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-card {
  width: 380px;
  padding: 28px 26px 22px;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 20px;
}

.login-hint {
  margin-top: 14px;
  font-size: 11px;
  color: var(--t3);
  text-align: center;
}
</style>
