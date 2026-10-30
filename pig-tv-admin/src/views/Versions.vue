<template>
  <div>
    <div class="page-head">
      <div>
        <h1>版本管理</h1>
        <p>上传 APK 后先存为草稿，确认无误再「发布」——发布后客户端即可检查到</p>
      </div>
      <div>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Upload" @click="openUpload">上传新版本</el-button>
      </div>
    </div>

    <div class="card">
      <el-table :data="rows" v-loading="loading" size="default" empty-text="还没有上传过版本">
        <el-table-column label="版本" min-width="140">
          <template #default="{ row }">
            <div class="ver-name">v{{ row.versionName }}</div>
            <div class="muted mono mini">code {{ row.versionCode }}</div>
          </template>
        </el-table-column>

        <el-table-column label="平台 / ABI" width="130">
          <template #default="{ row }">
            <span class="pill pill-muted">{{ platformText(row.platform) }}</span>
            <span class="muted mini-block">{{ row.abi }}</span>
          </template>
        </el-table-column>

        <el-table-column label="大小" width="80">
          <template #default="{ row }">{{ fmtSize(row.fileSize) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span v-if="row.status === 'published'" class="pill pill-ok">已发布</span>
            <span v-else-if="row.status === 'draft'" class="pill pill-warn">草稿</span>
            <span v-else class="pill pill-muted">已下架</span>
          </template>
        </el-table-column>

        <el-table-column label="强制" width="65">
          <template #default="{ row }">
            <span :class="row.forceUpdate === 1 ? 'danger-text' : 'muted'">{{ row.forceUpdate === 1 ? '是' : '否' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="下载" width="70">
          <template #default="{ row }">{{ row.downloadCount }}</template>
        </el-table-column>

        <el-table-column label="发布时间" width="110">
          <template #default="{ row }">{{ shortTime(row.publishedAt) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status !== 'published'" link type="success" @click="publish(row)">发布</el-button>
            <el-button v-else link type="warning" @click="offline(row)">下架</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link @click="copyUrl(row)">复制链接</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 上传 -->
    <el-dialog v-model="uploadVisible" title="上传新版本" width="600px" @closed="resetUpload">
      <el-form label-width="120px" label-position="left">
        <el-form-item label="安装包">
          <el-upload
            drag
            action="#"
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
            :file-list="fileList"
            accept=".apk"
            style="width: 100%"
          >
            <el-icon class="el-icon--upload" :size="32"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖入 APK 或<em>点击选择</em></div>
            <template #tip>
              <div class="upload-tip">单文件不超过 200MB。上传后服务端会计算 SHA-256 供客户端校验。</div>
            </template>
          </el-upload>
        </el-form-item>

        <el-form-item label="版本名称">
          <el-input v-model="form.versionName" placeholder="例如 1.0.11" />
        </el-form-item>

        <el-form-item label="版本号 code">
          <el-input-number v-model="form.versionCode" :min="1" :max="999999" controls-position="right" />
          <span class="hint-inline">必须大于线上版本，否则客户端不会提示更新</span>
        </el-form-item>

        <el-form-item label="平台">
          <el-radio-group v-model="form.platform">
            <el-radio-button value="phone">手机</el-radio-button>
            <el-radio-button value="tv">电视</el-radio-button>
            <el-radio-button value="all">通用</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="CPU 架构">
          <el-select v-model="form.abi" style="width: 200px">
            <el-option label="universal（通用，推荐）" value="universal" />
            <el-option label="arm64（64 位）" value="arm64" />
            <el-option label="armeabi（32 位）" value="armeabi" />
          </el-select>
        </el-form-item>

        <el-form-item label="包名">
          <el-input v-model="form.packageName" />
        </el-form-item>

        <el-form-item label="更新内容">
          <el-input
            v-model="form.changelog"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="· 修复电视端分类为空&#10;· 优化起播速度"
          />
        </el-form-item>

        <el-form-item label="强制更新">
          <el-switch v-model="form.forceUpdate" />
          <span class="hint-inline">开启后旧版无法跳过本次更新</span>
        </el-form-item>

        <el-form-item label="最低支持版本">
          <el-input-number v-model="form.minSupportedVersionCode" :min="0" controls-position="right" />
          <span class="hint-inline">低于此 code 的客户端一律强制更新，0 表示不限制</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button :loading="saving" @click="submitUpload(false)">存为草稿</el-button>
        <el-button type="primary" :loading="saving" @click="submitUpload(true)">上传并发布</el-button>
      </template>
    </el-dialog>

    <!-- 编辑 -->
    <el-dialog v-model="editVisible" title="编辑版本" width="520px">
      <el-form label-width="110px" label-position="left">
        <el-form-item label="更新内容">
          <el-input v-model="editForm.changelog" type="textarea" :rows="6" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="强制更新">
          <el-switch v-model="editForm.forceUpdateBool" />
        </el-form-item>
        <el-form-item label="最低支持版本">
          <el-input-number v-model="editForm.minSupportedVersionCode" :min="0" controls-position="right" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Upload, UploadFilled } from '@element-plus/icons-vue'
import { api } from '../api'

const rows = ref([])
const loading = ref(false)
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await api.versions({ page: 1, size: 50 })
    rows.value = res.items || []
  } finally {
    loading.value = false
  }
}

function platformText(p) {
  return p === 'phone' ? '手机' : p === 'tv' ? '电视' : '通用'
}

function fmtSize(bytes) {
  if (!bytes) return '—'
  const mb = bytes / 1024 / 1024
  return mb >= 1 ? mb.toFixed(1) + ' MB' : (bytes / 1024).toFixed(0) + ' KB'
}

function shortTime(t) {
  if (!t) return '—'
  return String(t).slice(5, 16)
}

// ---------------- 上传 ----------------
const uploadVisible = ref(false)
const fileList = ref([])
const pickedFile = ref(null)

const form = reactive({
  versionName: '',
  versionCode: 1,
  platform: 'phone',
  abi: 'universal',
  packageName: 'com.github.tvbox.osc.tk',
  changelog: '',
  forceUpdate: false,
  minSupportedVersionCode: 0
})

function openUpload() {
  const maxCode = rows.value.reduce((m, r) => Math.max(m, r.versionCode || 0), 1000)
  form.versionCode = maxCode + 1
  form.versionName = ''
  uploadVisible.value = true
}

function resetUpload() {
  fileList.value = []
  pickedFile.value = null
  form.changelog = ''
  form.forceUpdate = false
  form.minSupportedVersionCode = 0
}

function onFileChange(file) {
  pickedFile.value = file.raw
  fileList.value = [file]
}

function onFileRemove() {
  pickedFile.value = null
  fileList.value = []
}

async function submitUpload(publishNow) {
  if (!pickedFile.value) {
    ElMessage.warning('请先选择 APK 文件')
    return
  }
  if (!form.versionName) {
    ElMessage.warning('请填写版本名称')
    return
  }
  const fd = new FormData()
  fd.append('file', pickedFile.value)
  fd.append('versionName', form.versionName)
  fd.append('versionCode', String(form.versionCode))
  fd.append('platform', form.platform)
  fd.append('abi', form.abi)
  fd.append('packageName', form.packageName)
  fd.append('changelog', form.changelog)
  fd.append('forceUpdate', form.forceUpdate ? '1' : '0')
  fd.append('minSupportedVersionCode', String(form.minSupportedVersionCode))
  fd.append('status', publishNow ? 'published' : 'draft')

  saving.value = true
  try {
    await api.uploadVersion(fd)
    ElMessage.success(publishNow ? '已上传并发布' : '已存为草稿')
    uploadVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

// ---------------- 发布 / 下架 / 编辑 ----------------
async function publish(row) {
  await api.updateVersion(row.id, { status: 'published' })
  ElMessage.success('已发布')
  load()
}

async function offline(row) {
  await ElMessageBox.confirm('下架后客户端将不再检测到该版本，确认继续？', '下架版本', { type: 'warning' })
  await api.updateVersion(row.id, { status: 'offline' })
  ElMessage.success('已下架')
  load()
}

const editVisible = ref(false)
const editTarget = ref(null)
const editForm = reactive({ changelog: '', forceUpdateBool: false, minSupportedVersionCode: 0 })

function openEdit(row) {
  editTarget.value = row
  editForm.changelog = row.changelog || ''
  editForm.forceUpdateBool = row.forceUpdate === 1
  editForm.minSupportedVersionCode = row.minSupportedVersionCode || 0
  editVisible.value = true
}

async function saveEdit() {
  saving.value = true
  try {
    await api.updateVersion(editTarget.value.id, {
      changelog: editForm.changelog,
      forceUpdate: editForm.forceUpdateBool,
      minSupportedVersionCode: editForm.minSupportedVersionCode
    })
    ElMessage.success('已保存')
    editVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function copyUrl(row) {
  try {
    await navigator.clipboard.writeText(row.fileUrl)
    ElMessage.success('下载链接已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择链接')
  }
}

onMounted(load)
</script>

<style scoped>
.ver-name {
  font-weight: 500;
  color: var(--ink-900);
}

.mini {
  font-size: 11px;
  line-height: 1.4;
}

.mini-block {
  display: block;
  font-size: 11px;
  line-height: 1.4;
}

.hint-inline {
  font-size: 12px;
  color: var(--ink-400);
  margin-left: 10px;
}

.upload-tip {
  font-size: 12px;
  color: var(--ink-400);
  margin-top: 6px;
}
</style>
