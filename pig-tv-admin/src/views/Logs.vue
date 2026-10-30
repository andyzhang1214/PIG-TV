<template>
  <div>
    <div class="page-head">
      <div>
        <h1>操作日志</h1>
        <p>后台所有写操作都会留痕，用于追溯误操作</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="card">
      <div class="toolbar">
        <el-select v-model="query.action" style="width: 170px" @change="reload">
          <el-option label="全部动作" value="all" />
          <el-option v-for="a in actions" :key="a.value" :label="a.label" :value="a.value" />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="搜索目标或操作人"
          clearable
          style="width: 220px"
          :prefix-icon="Search"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-button type="primary" @click="reload">查询</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" size="default" empty-text="暂无日志">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ row.createdAt }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="120">
          <template #default="{ row }">{{ row.adminName }}</template>
        </el-table-column>
        <el-table-column label="动作" width="130">
          <template #default="{ row }">
            <span :class="'pill pill-' + tagType(row.action)">{{ actionText(row.action) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="目标" min-width="150">
          <template #default="{ row }">{{ row.target || '—' }}</template>
        </el-table-column>
        <el-table-column label="详情" min-width="280">
          <template #default="{ row }">
            <el-tooltip v-if="row.detail" :content="row.detail" placement="top">
              <span class="mono detail ellipsis">{{ row.detail }}</span>
            </el-tooltip>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="IP" width="140">
          <template #default="{ row }"><span class="mono">{{ row.ip || '—' }}</span></template>
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
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api'

const rows = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ action: 'all', keyword: '', page: 1, size: 20 })

const actions = [
  { value: 'ban', label: '封禁账号' },
  { value: 'unban', label: '解封账号' },
  { value: 'reset_pwd', label: '重置密码' },
  { value: 'delete_user', label: '删除账号' },
  { value: 'lock_segment', label: '锁定网段' },
  { value: 'publish', label: '上传版本' },
  { value: 'version_edit', label: '编辑版本' },
  { value: 'version_offline', label: '下架版本' },
  { value: 'source_create', label: '新增片源' },
  { value: 'source_edit', label: '编辑片源' },
  { value: 'source_delete', label: '删除片源' },
  { value: 'ip_rule_edit', label: '修改风控规则' },
  { value: 'ip_list_add', label: '添加 IP 名单' },
  { value: 'ip_list_delete', label: '删除 IP 名单' }
]

function actionText(a) {
  return actions.find((x) => x.value === a)?.label || a
}

/** 返回 pill 的颜色修饰名（对应 main.css 里的 .pill-xxx） */
function tagType(a) {
  if (a?.startsWith('delete') || a === 'ban') return 'danger'
  if (a === 'unban' || a === 'publish') return 'ok'
  if (a?.startsWith('ip')) return 'warn'
  return 'brand'
}

async function load() {
  loading.value = true
  try {
    const res = await api.logs({
      action: query.action === 'all' ? undefined : query.action,
      keyword: query.keyword,
      page: query.page,
      size: query.size
    })
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

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.detail {
  color: var(--ink-500);
}

.ellipsis {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
