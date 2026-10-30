import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: '/',
  timeout: 20000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('pig_admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && body.code !== undefined && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body && body.data !== undefined ? body.data : body
  },
  (err) => {
    const status = err.response?.status
    const body = err.response?.data
    if (status === 401) {
      localStorage.removeItem('pig_admin_token')
      localStorage.removeItem('pig_admin_name')
      if (!location.pathname.startsWith('/login')) {
        location.href = '/login'
      }
    } else {
      ElMessage.error(body?.message || err.message || '网络异常')
    }
    return Promise.reject(err)
  }
)

export const api = {
  login: (data) => http.post('/admin/auth/login', data),
  profile: () => http.get('/admin/auth/profile'),
  changeAdminPassword: (data) => http.post('/admin/auth/change-password', data),

  overview: () => http.get('/admin/stats/overview'),

  users: (params) => http.get('/admin/users', { params }),
  userDetail: (id) => http.get(`/admin/users/${id}`),
  userSegments: (id) => http.get(`/admin/users/${id}/segments`),
  resetPassword: (id, data) => http.patch(`/admin/users/${id}/password`, data),
  setUserStatus: (id, data) => http.patch(`/admin/users/${id}/status`, data),
  unbanUser: (id) => http.post(`/admin/users/${id}/unban`),
  lockSegment: (id, data) => http.post(`/admin/users/${id}/lock-segment`, data),
  deleteUser: (id) => http.delete(`/admin/users/${id}`),

  versions: (params) => http.get('/admin/versions', { params }),
  uploadVersion: (formData) => http.post('/admin/versions', formData, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 600000 }),
  updateVersion: (id, data) => http.patch(`/admin/versions/${id}`, data),
  deleteVersion: (id) => http.delete(`/admin/versions/${id}`),

  sources: () => http.get('/admin/sources'),
  createSource: (data) => http.post('/admin/sources', data),
  updateSource: (id, data) => http.patch(`/admin/sources/${id}`, data),
  deleteSource: (id) => http.delete(`/admin/sources/${id}`),
  testSource: (id) => http.post(`/admin/sources/${id}/test`),

  ipRule: () => http.get('/admin/ip-rule'),
  saveIpRule: (data) => http.put('/admin/ip-rule', data),
  ipList: (params) => http.get('/admin/ip-list', { params }),
  addIp: (data) => http.post('/admin/ip-list', data),
  deleteIp: (id) => http.delete(`/admin/ip-list/${id}`),

  logs: (params) => http.get('/admin/audit-logs', { params })
}

export default http
