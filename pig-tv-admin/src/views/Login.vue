<template>
  <div class="login-page">
    <div class="login-card">
      <div class="head">
        <div class="mark">P</div>
        <div>
          <h1>猪猪影视 · 运营后台</h1>
          <p>管理用户、版本与片源接口</p>
        </div>
      </div>

      <form class="form" @submit.prevent="submit">
        <label class="field">
          <span class="label">账号</span>
          <el-input
            v-model="form.username"
            size="large"
            placeholder="管理员账号"
            autocomplete="username"
            :prefix-icon="User"
          />
        </label>

        <label class="field">
          <span class="label">密码</span>
          <el-input
            v-model="form.password"
            type="password"
            size="large"
            show-password
            placeholder="登录密码"
            autocomplete="current-password"
            :prefix-icon="Lock"
            @keyup.enter="submit"
          />
        </label>

        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="err" />

        <el-button
          type="primary"
          size="large"
          class="submit"
          :loading="loading"
          native-type="submit"
        >
          登录
        </el-button>
      </form>

      <p class="tip">
        首次部署的默认账号见服务端启动日志，登录后请立即在右上角修改密码。
      </p>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { api } from '../api'

const router = useRouter()
const route = useRoute()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!form.username || !form.password) {
    error.value = '请填写账号和密码'
    return
  }
  error.value = ''
  loading.value = true
  try {
    const data = await api.login({ username: form.username, password: form.password })
    localStorage.setItem('pig_admin_token', data.token)
    localStorage.setItem('pig_admin_name', data.username)
    ElMessage.success('登录成功')
    router.replace(route.query.redirect || '/dashboard')
  } catch (e) {
    error.value = e?.response?.data?.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ink-900);
  padding: 24px;
}

.login-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 16px;
  padding: 32px;
}

.head {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 28px;
}

.mark {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  background: var(--brand);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 500;
}

h1 {
  font-size: 16px;
  font-weight: 500;
  color: var(--ink-900);
  margin: 0;
}

.head p {
  font-size: 12px;
  color: var(--ink-400);
  margin: 2px 0 0;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.label {
  font-size: 12px;
  color: var(--ink-500);
}

.err {
  margin: 0;
}

.submit {
  width: 100%;
  margin-top: 4px;
}

.tip {
  font-size: 12px;
  color: var(--ink-400);
  line-height: 1.6;
  margin: 20px 0 0;
}
</style>
