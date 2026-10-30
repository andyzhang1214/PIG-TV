<template>
  <div>
    <div class="page-head">
      <div>
        <h1>用户管理</h1>
        <p>共 {{ total }} 个账号，在线判定为 5 分钟内有心跳</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索用户名"
          clearable
          style="width: 220px"
          :prefix-icon="Search"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-select v-model="query.state" style="width: 140px" @change="reload">
          <el-option label="全部状态" value="all" />
          <el-option label="在线" value="online" />
          <el-option label="正常" value="normal" />
          <el-option label="封禁中" value="banned" />
        </el-select>
        <el-button type="primary" @click="reload">查询</el-button>
        <span class="spacer"></span>
      </div>

      <el-table :data="rows" v-loading="loading" size="default" empty-text="没有符合条件的账号">
        <el-table-column label="账号" min-width="160">
          <template #default="{ row }">
            <div class="user-cell">
              <span class="dot" :class="row.online ? 'on' : 'off'"></span>
              <div>
                <div>{{ row.username }}</div>
                <div class="muted mini">{{ (row.createdAt || '').slice(0, 10) || '—' }} 注册</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <span v-if="row.status === 1" class="pill pill-danger">
              {{ row.banType === 'auto_ip' ? '风控封禁' : '已封禁' }}
            </span>
            <span v-else class="pill pill-ok">正常</span>
          </template>
        </el-table-column>

        <el-table-column label="在线" width="80">
          <template #default="{ row }">
            <span :class="row.online ? 'ok-text' : 'muted'">{{ row.online ? '在线' : '离线' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="最后 IP" width="130">
          <template #default="{ row }">
            <span class="mono">{{ row.lastIp || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="最后登录" width="160">
          <template #default="{ row }">{{ row.lastLoginAt || '—' }}</template>
        </el-table-column>

        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 1" link type="success" @click="unban(row)">解封</el-button>
            <el-button v-else link type="warning" @click="openBan(row)">封号</el-button>
            <el-dropdown trigger="click" @command="(c) => onMore(c, row)">
              <el-button link type="primary" style="margin-left: 8px">
                更多<el-icon :size="12" style="margin-left: 2px"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="password">重置密码</el-dropdown-item>
                  <el-dropdown-item command="segment">锁定网段</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除账号</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          layout="prev, pager, next, total"
          :total="total"
          :page-size="query.size"
          :current-page="query.page"
          @current-change="onPage"
        />
      </div>
    </div>

    <!-- 封号弹窗 -->
    <el-dialog v-model="banVisible" title="封禁账号" width="460px" @closed="resetBan">
      <p class="dialog-sub">账号 <strong>{{ banTarget?.username }}</strong> 封禁后所有设备立即失效。</p>
      <el-form label-width="90px" label-position="left">
        <el-form-item label="封禁时长">
          <div class="inline">
            <el-input-number v-model="banForm.amount" :min="1" :max="9999" :disabled="banForm.permanent" controls-position="right" style="width: 120px" />
            <el-select v-model="banForm.unit" :disabled="banForm.permanent" style="width: 110px">
              <el-option label="小时" value="hour" />
              <el-option label="天" value="day" />
              <el-option label="月" value="month" />
            </el-select>
            <el-checkbox v-model="banForm.permanent">永久</el-checkbox>
          </div>
        </el-form-item>
        <el-form-item label="解封时间">
          <span class="mono">{{ banUntilText }}</span>
        </el-form-item>
        <el-form-item label="封禁原因">
          <el-input v-model="banForm.reason" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="例如：多账号共用、倒卖账号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="banVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="doBan">确认封禁</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="pwdVisible" title="重置密码" width="420px">
      <p class="dialog-sub">账号 <strong>{{ activeRow?.username }}</strong> 的密码将被重置，其所有设备会被踢下线。</p>
      <el-input v-model="newPassword" type="password" show-password placeholder="新密码，8-32 位且含字母和数字" />
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doResetPassword">确认重置</el-button>
      </template>
    </el-dialog>

    <!-- 锁定网段 -->
    <el-dialog v-model="segVisible" title="限定登录网段" width="480px">
      <p class="dialog-sub">填写允许登录的网段（逗号分隔），留空表示不限制。命中其他网段会被直接拒绝登录。</p>
      <el-input v-model="segmentInput" placeholder="例如：113.88.45.0/24, 2408:8207:1a2b:3c4d::/64" />
      <template #footer>
        <el-button @click="segVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doLockSegment">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" :title="detail?.username || '用户详情'" size="560px">
      <div v-if="detail" class="detail">
        <div class="kv">
          <div><span class="k">账号</span><span>{{ detail.username }}</span></div>
          <div><span class="k">状态</span>
            <span v-if="detail.status === 1" class="pill pill-danger">封禁至 {{ detail.banUntil }}</span>
            <span v-else class="pill pill-ok">正常</span>
          </div>
          <div><span class="k">注册 IP</span><span class="mono">{{ detail.registerIp || '—' }}</span></div>
          <div><span class="k">最后 IP</span><span class="mono">{{ detail.lastIp || '—' }}</span></div>
          <div><span class="k">注册时间</span><span>{{ detail.createdAt || '—' }}</span></div>
          <div><span class="k">设备上限</span><span>{{ detail.deviceLimit }}</span></div>
          <div><span class="k">锁定网段</span><span class="mono">{{ detail.lockedSegments || '不限制' }}</span></div>
          <div v-if="detail.banReason"><span class="k">封禁原因</span><span>{{ detail.banReason }}</span></div>
        </div>

        <h3 class="sec">登录设备与网段</h3>
        <el-table :data="detail.segments || []" size="small" empty-text="暂无记录">
          <el-table-column label="设备" min-width="120">
            <template #default="{ row }">{{ row.deviceName || row.deviceId?.slice(0, 10) }}</template>
          </el-table-column>
          <el-table-column label="平台" width="70">
            <template #default="{ row }">{{ row.platform === 'tv' ? '电视' : '手机' }}</template>
          </el-table-column>
          <el-table-column label="网段" min-width="150">
            <template #default="{ row }"><span class="mono">{{ row.ipSeg || row.ip }}</span></template>
          </el-table-column>
          <el-table-column label="最后心跳" width="150">
            <template #default="{ row }">{{ row.lastSeenAt }}</template>
          </el-table-column>
        </el-table>

        <h3 class="sec">网段风控封禁记录</h3>
        <el-table :data="detail.banLogs || []" size="small" empty-text="没有自动封禁记录">
          <el-table-column label="触发网段" min-width="240">
            <template #default="{ row }"><span class="mono">{{ row.segments }}</span></template>
          </el-table-column>
          <el-table-column label="封至" width="150">
            <template #default="{ row }">{{ row.banUntil }}</template>
          </el-table-column>
          <el-table-column label="是否已解封" width="100">
            <template #default="{ row }">{{ row.unbannedBy ? '是' : '否' }}</template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, ArrowDown } from '@element-plus/icons-vue'
import { api } from '../api'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)

const query = reactive({ keyword: '', state: 'all', page: 1, size: 20 })

async function load() {
  loading.value = true
  try {
    const res = await api.users({ keyword: query.keyword, state: query.state, page: query.page, size: query.size })
    rows.value = res.items || []
    total.value = res.total || 0
  } finally {
    loading.value = false
  }
}

function reload() {
  query.page = 1
  load()
}

function onPage(p) {
  query.page = p
  load()
}

// ---------------- 封禁 ----------------
const banVisible = ref(false)
const banTarget = ref(null)
const banForm = reactive({ amount: 3, unit: 'day', permanent: false, reason: '' })

const banHours = computed(() => {
  if (banForm.permanent) return null
  const n = banForm.amount || 1
  if (banForm.unit === 'hour') return n
  if (banForm.unit === 'day') return n * 24
  return n * 24 * 30
})

const banUntilText = computed(() => {
  if (banForm.permanent) return '永久封禁'
  const h = banHours.value || 0
  const d = new Date(Date.now() + h * 3600 * 1000)
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
})

function openBan(row) {
  banTarget.value = row
  banVisible.value = true
}

function resetBan() {
  banForm.amount = 3
  banForm.unit = 'day'
  banForm.permanent = false
  banForm.reason = ''
}

async function doBan() {
  saving.value = true
  try {
    await api.setUserStatus(banTarget.value.id, {
      action: 'ban',
      hours: banHours.value,
      permanent: banForm.permanent,
      reason: banForm.reason
    })
    ElMessage.success('已封禁')
    banVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function unban(row) {
  await api.unbanUser(row.id)
  ElMessage.success('已解封')
  load()
}

// ---------------- 更多操作 ----------------
const activeRow = ref(null)
const pwdVisible = ref(false)
const newPassword = ref('')
const segVisible = ref(false)
const segmentInput = ref('')

function onMore(cmd, row) {
  activeRow.value = row
  if (cmd === 'password') {
    newPassword.value = ''
    pwdVisible.value = true
  } else if (cmd === 'segment') {
    segmentInput.value = row.lockedSegments || ''
    segVisible.value = true
  } else if (cmd === 'delete') {
    ElMessageBox.prompt(`删除后不可恢复，请输入账号名「${row.username}」确认`, '删除账号', {
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger',
      inputPlaceholder: row.username
    }).then(async ({ value }) => {
      if (value !== row.username) {
        ElMessage.warning('账号名不匹配，已取消')
        return
      }
      await api.deleteUser(row.id)
      ElMessage.success('已删除')
      load()
    }).catch(() => {})
  }
}

async function doResetPassword() {
  if (!newPassword.value || newPassword.value.length < 8) {
    ElMessage.warning('密码至少 8 位')
    return
  }
  saving.value = true
  try {
    await api.resetPassword(activeRow.value.id, { newPassword: newPassword.value })
    ElMessage.success('密码已重置')
    pwdVisible.value = false
  } finally {
    saving.value = false
  }
}

async function doLockSegment() {
  saving.value = true
  try {
    await api.lockSegment(activeRow.value.id, { segments: segmentInput.value })
    ElMessage.success('已保存')
    segVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

// ---------------- 详情 ----------------
const detailVisible = ref(false)
const detail = ref(null)

async function openDetail(row) {
  detail.value = null
  detailVisible.value = true
  detail.value = await api.userDetail(row.id)
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.spacer {
  flex: 1;
}

.user-cell {
  display: flex;
  align-items: flex-start;
  gap: 2px;
}

.mini {
  font-size: 11px;
  line-height: 1.4;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.inline {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.dialog-sub {
  font-size: 12px;
  color: var(--ink-500);
  margin: 0 0 16px;
}

.detail .kv {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 16px;
  font-size: 13px;
}

.detail .kv > div {
  display: flex;
  gap: 8px;
}

.detail .k {
  color: var(--ink-400);
  width: 68px;
  flex: 0 0 68px;
}

.sec {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-900);
  margin: 24px 0 10px;
}
</style>
