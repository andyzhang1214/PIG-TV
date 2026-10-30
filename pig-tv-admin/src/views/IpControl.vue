<template>
  <div>
    <div class="page-head">
      <div>
        <h1>网段风控</h1>
        <p>同一账号同时在多个网络登录会被自动封禁；白名单内的 IP 永不触发</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="grid">
      <div class="card">
        <div class="card-title">
          <span>触发规则</span>
          <span class="sub">改动立即生效</span>
        </div>

        <el-form label-width="132px" label-position="left" v-loading="loading">
          <el-form-item label="启用风控">
            <el-switch v-model="rule.enabled" />
            <span class="hint">关闭后完全不判定，已封禁的账号不受影响</span>
          </el-form-item>

          <el-form-item label="网段上限">
            <el-input-number v-model="rule.maxSegments" :min="1" :max="20" controls-position="right" style="width: 130px" />
            <span class="hint">同时在线超过该数量即封禁，默认 3</span>
          </el-form-item>

          <el-form-item label="封禁时长">
            <el-input-number v-model="rule.banHours" :min="1" :max="8760" controls-position="right" style="width: 130px" />
            <span class="hint">小时，默认 72（3 天）</span>
          </el-form-item>

          <el-form-item label="单设备容忍">
            <el-input-number v-model="rule.sameDeviceGrace" :min="0" :max="1440" controls-position="right" style="width: 130px" />
            <span class="hint">分钟内同设备换 IP 只算最新网段</span>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="saving" @click="saveRule">保存规则</el-button>
          </el-form-item>
        </el-form>
      </div>

      <div class="card">
        <div class="card-title">
          <span>判定口径</span>
          <span class="sub">改这段文字前先看代码</span>
        </div>
        <ul class="notes">
          <li>网段取法：IPv4 前三段（/24），IPv6 前四段（/64）。</li>
          <li>只统计 5 分钟内有心跳的会话，历史登录不算。</li>
          <li>每台设备只取最新一条会话的网段，一台设备永远只贡献 1 个网段。</li>
          <li>因此超过上限必然意味着「多台设备在多个网络」，而不是单设备抖动。</li>
          <li>自动封禁会留下证据（网段、设备、时间），在用户详情里可查。</li>
          <li>人工解封后 24 小时内不再自动触发，避免解封即再封。</li>
        </ul>
      </div>
    </div>

    <div class="card" style="margin-top: 16px">
      <div class="card-title">
        <span>IP 名单</span>
        <div>
          <el-radio-group v-model="listType" size="small" style="margin-right: 10px" @change="loadList">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="allow">白名单</el-radio-button>
            <el-radio-button value="deny">黑名单</el-radio-button>
          </el-radio-group>
          <el-button type="primary" size="small" :icon="Plus" @click="addVisible = true">添加</el-button>
        </div>
      </div>

      <el-table :data="list" size="default" empty-text="还没有配置 IP 名单">
        <el-table-column label="IP / 网段" min-width="220">
          <template #default="{ row }"><span class="mono">{{ row.cidr }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <span :class="row.listType === 'allow' ? 'pill pill-ok' : 'pill pill-danger'">
              {{ row.listType === 'allow' ? '白名单' : '黑名单' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="备注" min-width="180">
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column label="添加时间" width="170">
          <template #default="{ row }">{{ row.createdAt }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeIp(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="addVisible" title="添加 IP 名单" width="460px" @closed="resetAdd">
      <el-form label-width="80px" label-position="left">
        <el-form-item label="类型">
          <el-radio-group v-model="addForm.listType">
            <el-radio-button value="allow">白名单</el-radio-button>
            <el-radio-button value="deny">黑名单</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="IP/网段">
          <el-input v-model="addForm.cidr" placeholder="113.88.45.0/24 或 1.2.3.4" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="addForm.remark" maxlength="200" placeholder="例如：公司出口" />
        </el-form-item>
      </el-form>
      <div class="dialog-note">
        白名单内的地址不参与网段计数；黑名单直接拒绝登录与心跳。
      </div>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="addIp">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Plus } from '@element-plus/icons-vue'
import { api } from '../api'

const loading = ref(false)
const saving = ref(false)
const rule = reactive({ enabled: true, maxSegments: 3, banHours: 72, sameDeviceGrace: 60 })
const list = ref([])
const listType = ref('')

async function load() {
  loading.value = true
  try {
    const r = await api.ipRule()
    Object.assign(rule, r)
    await loadList()
  } finally {
    loading.value = false
  }
}

async function loadList() {
  list.value = await api.ipList({ type: listType.value || undefined })
}

async function saveRule() {
  saving.value = true
  try {
    const r = await api.saveIpRule({ ...rule })
    Object.assign(rule, r)
    ElMessage.success('规则已保存')
  } finally {
    saving.value = false
  }
}

const addVisible = ref(false)
const addForm = reactive({ cidr: '', listType: 'allow', remark: '' })

function resetAdd() {
  addForm.cidr = ''
  addForm.listType = 'allow'
  addForm.remark = ''
}

async function addIp() {
  if (!addForm.cidr) {
    ElMessage.warning('请填写 IP 或网段')
    return
  }
  saving.value = true
  try {
    await api.addIp({ ...addForm })
    ElMessage.success('已添加')
    addVisible.value = false
    loadList()
  } finally {
    saving.value = false
  }
}

async function removeIp(row) {
  await ElMessageBox.confirm(`确认删除「${row.cidr}」？`, '删除名单', { type: 'warning' })
  await api.deleteIp(row.id)
  ElMessage.success('已删除')
  loadList()
}

onMounted(load)
</script>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 16px;
}

@media (max-width: 1000px) {
  .grid {
    grid-template-columns: 1fr;
  }
}

.hint {
  font-size: 12px;
  color: var(--ink-400);
  margin-left: 10px;
  line-height: 1.5;
}

.notes {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--ink-500);
  line-height: 1.9;
}

.dialog-note {
  font-size: 12px;
  color: var(--ink-400);
  margin-top: 4px;
}
</style>
