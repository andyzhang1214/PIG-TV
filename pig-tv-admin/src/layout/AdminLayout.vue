<template>
  <div class="shell">
    <aside class="side">
      <div class="brand">
        <div class="mark">P</div>
        <div class="brand-text">
          <div class="name">猪猪影视</div>
          <div class="tag">运营后台</div>
        </div>
      </div>

      <nav class="nav" aria-label="主导航">
        <router-link
          v-for="item in menus"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: route.path === item.path }"
        >
          <el-icon :size="18"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
          <span v-if="item.badge" class="badge">{{ item.badge }}</span>
        </router-link>
      </nav>

      <div class="side-foot">
        <div class="ver">v1.0.0</div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <span class="muted">后台</span>
          <span class="sep">/</span>
          <span>{{ currentTitle }}</span>
        </div>
        <div class="right">
          <el-dropdown trigger="click" @command="onCommand">
            <button class="user" type="button">
              <span class="avatar">{{ initial }}</span>
              <span class="uname">{{ adminName }}</span>
              <el-icon :size="14"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="password">修改密码</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </main>
    </div>

    <el-dialog v-model="pwdVisible" title="修改登录密码" width="420px">
      <el-form label-width="86px" label-position="left">
        <el-form-item label="原密码">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwdForm.newPassword" type="password" show-password autocomplete="new-password" placeholder="至少 8 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="savePassword">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'

const route = useRoute()
const router = useRouter()

const menus = [
  { path: '/dashboard', label: '仪表盘', icon: 'DataLine' },
  { path: '/users', label: '用户管理', icon: 'User' },
  { path: '/versions', label: '版本管理', icon: 'Box' },
  { path: '/sources', label: '片源接口', icon: 'Link' },
  { path: '/ip', label: '网段风控', icon: 'Location' },
  { path: '/logs', label: '操作日志', icon: 'Document' }
]

const currentTitle = computed(() => menus.find((m) => m.path === route.path)?.label || '仪表盘')
const adminName = computed(() => localStorage.getItem('pig_admin_name') || 'admin')
const initial = computed(() => adminName.value.slice(0, 1).toUpperCase())

const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '' })

function onCommand(cmd) {
  if (cmd === 'password') {
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdVisible.value = true
  } else if (cmd === 'logout') {
    ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' }).then(() => {
      localStorage.removeItem('pig_admin_token')
      localStorage.removeItem('pig_admin_name')
      router.replace('/login')
    }).catch(() => {})
  }
}

async function savePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('请填写完整')
    return
  }
  pwdSaving.value = true
  try {
    await api.changeAdminPassword({ ...pwdForm })
    ElMessage.success('密码已更新')
    pwdVisible.value = false
  } finally {
    pwdSaving.value = false
  }
}
</script>

<style scoped>
.shell {
  display: flex;
  height: 100%;
  min-height: 100vh;
}

.side {
  width: 216px;
  flex: 0 0 216px;
  background: var(--ink-900);
  display: flex;
  flex-direction: column;
  padding: 18px 12px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px 20px;
}

.mark {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: var(--brand);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  font-weight: 500;
}

.brand-text .name {
  color: #f2f3f7;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.2;
}

.brand-text .tag {
  color: #7c8499;
  font-size: 11px;
  margin-top: 2px;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 44px;
  padding: 0 12px;
  border-radius: 8px;
  color: #a8b0c2;
  font-size: 13px;
  transition: background-color 200ms ease, color 200ms ease;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #e8eaf0;
}

.nav-item.active {
  background: var(--brand);
  color: #fff;
}

.badge {
  margin-left: auto;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  border-radius: 9px;
  font-size: 11px;
  padding: 0 6px;
  line-height: 18px;
}

.side-foot {
  padding: 10px;
  color: #5d6579;
  font-size: 11px;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 56px;
  flex: 0 0 56px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}

.crumb {
  font-size: 13px;
  color: var(--ink-700);
  display: flex;
  align-items: center;
  gap: 8px;
}

.sep {
  color: #c9ced9;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 6px 8px;
  border-radius: 8px;
  color: var(--ink-700);
  font-size: 13px;
  transition: background-color 200ms ease;
}

.user:hover {
  background: #f2f4f8;
}

.avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--brand-soft);
  color: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 500;
}

.content {
  flex: 1;
  overflow: auto;
  padding: 24px;
}

@media (max-width: 900px) {
  .side {
    width: 64px;
    flex: 0 0 64px;
    padding: 18px 8px;
  }
  .brand-text,
  .nav-item span:not(.badge),
  .side-foot {
    display: none;
  }
  .nav-item {
    justify-content: center;
    padding: 0;
  }
  .content {
    padding: 16px;
  }
}
</style>
