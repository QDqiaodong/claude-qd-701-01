import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  }
)

/** 模块一：机库机位 */
export const bayApi = {
  list: () => http.get('/bays'),
  create: (data) => http.post('/bays', data),
  occupy: (id, aircraftId) => http.post(`/bays/${id}/occupy`, { aircraftId }),
  release: (id) => http.post(`/bays/${id}/release`),
  setStatus: (id, status) => http.post(`/bays/${id}/status`, { status })
}

/** 模块二：飞机台账 */
export const aircraftApi = {
  list: () => http.get('/aircraft'),
  /** @Subselect 只读视图实体汇总出来的工卡数据 */
  cardSummary: () => http.get('/aircraft/card-summary'),
  create: (data) => http.post('/aircraft', data),
  setAirworthy: (id, airworthy) => http.post(`/aircraft/${id}/airworthy`, { airworthy }),
  logFlight: (id, data) => http.post(`/aircraft/${id}/flight-log`, data)
}

/** 模块三：定检工卡 */
export const cardApi = {
  list: (aircraftId) => http.get('/work-cards', { params: aircraftId ? { aircraftId } : {} }),
  timeline: (aircraftId) => http.get('/work-cards/timeline', { params: aircraftId ? { aircraftId } : {} }),
  create: (data) => http.post('/work-cards', data),
  start: (id, executor) => http.post(`/work-cards/${id}/start`, { executor }),
  finish: (id, actualHour) => http.post(`/work-cards/${id}/finish`, actualHour ? { actualHour } : {}),
  inspect: (id, inspector) => http.post(`/work-cards/${id}/inspect`, { inspector }),
  cancel: (id) => http.post(`/work-cards/${id}/cancel`),
  reopen: (id, reason) => http.post(`/work-cards/${id}/reopen`, reason ? { reason } : {}),
  reassign: (id, aircraftId) => http.post(`/work-cards/${id}/reassign`, { aircraftId })
}

/** 模块四：故障保留 */
export const defectApi = {
  list: (aircraftId) => http.get('/defects', { params: aircraftId ? { aircraftId } : {} }),
  overdue: () => http.get('/defects/overdue'),
  create: (data) => http.post('/defects', data),
  close: (id) => http.post(`/defects/${id}/close`)
}

/** 模块五：航材与借还 */
export const partApi = {
  list: () => http.get('/parts'),
  create: (data) => http.post('/parts', data),
  loans: (partId) => http.get('/parts/loans', { params: partId ? { partId } : {} }),
  loanOut: (data) => http.post('/parts/loans', data),
  returnLoan: (id, quantity) => http.post(`/parts/loans/${id}/return`, quantity ? { quantity } : {})
}

/** 机库总览计数器 */
export const hangarApi = {
  overview: () => http.get('/hangar/overview')
}

/** 模块六：出厂放行审签（所有门禁/失效/并发判定都在服务端） */
export const releaseApi = {
  overview: (aircraftId) => http.get('/releases/overview', { params: aircraftId ? { aircraftId } : {} }),
  gate: (aircraftId) => http.get('/releases/gate', { params: { aircraftId } }),
  list: (aircraftId) => http.get('/releases', { params: aircraftId ? { aircraftId } : {} }),
  detail: (id) => http.get(`/releases/${id}`),
  create: (data) => http.post('/releases', data),
  saveRetainBasis: (id, data) => http.post(`/releases/${id}/retain-basis`, data),
  submit: (id, operator) => http.post(`/releases/${id}/submit`, { operator }),
  mechSign: (id, signer) => http.post(`/releases/${id}/mech-sign`, { signer }),
  confirmDefect: (id, data) => http.post(`/releases/${id}/confirm-defect`, data),
  qaApprove: (id, signer) => http.post(`/releases/${id}/qa-approve`, { signer }),
  reject: (id, data) => http.post(`/releases/${id}/reject`, data)
}

/** 模块七：适航指令落实追踪（期限/状态/进度全部服务端判定） */
export const adApi = {
  overview: () => http.get('/ad/overview'),
  directives: () => http.get('/ad/directives'),
  revisions: (directiveId) => http.get(`/ad/directives/${directiveId}/revisions`),
  revision: (id) => http.get(`/ad/revisions/${id}`),
  createDirective: (data) => http.post('/ad/directives', data),
  revise: (directiveId, data) => http.post(`/ad/directives/${directiveId}/revise`, data),
  editRevision: (id, data) => http.post(`/ad/revisions/${id}`, data),
  recompute: (id, operator) => http.post(`/ad/revisions/${id}/recompute`, { operator }),
  compliances: (params) => http.get('/ad/compliances', { params }),
  compliance: (id) => http.get(`/ad/compliances/${id}`),
  closedCards: (aircraftId) => http.get(`/ad/aircraft/${aircraftId}/closed-cards`),
  // multipart：登记落实（同机已关闭工卡 + 登记凭据或上传文件）
  comply: (formData) => http.post('/ad/compliances', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }),
  linkCard: (id, data) => http.post(`/ad/compliances/${id}/cards`, data),
  addEvidence: (id, data) => http.post(`/ad/compliances/${id}/evidence`, data),
  uploadEvidence: (id, formData) => http.post(`/ad/compliances/${id}/evidence-upload`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  }),
  markEvidenceMissing: (id, data) => http.post(`/ad/evidence/${id}/mark-missing`, data),
  reverify: (id, data) => http.post(`/ad/compliances/${id}/reverify`, data),
  events: (params) => http.get('/ad/events', { params }),
  batches: (revisionId) => http.get('/ad/batches', { params: revisionId ? { revisionId } : {} }),
  batch: (id) => http.get(`/ad/batches/${id}`),
  retryBatch: (id, operator) => http.post(`/ad/batches/${id}/retry`, { operator })
}
