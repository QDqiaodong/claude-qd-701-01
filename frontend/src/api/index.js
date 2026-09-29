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
  cancel: (id) => http.post(`/work-cards/${id}/cancel`)
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

/** 模块七：适航指令落实追踪（适用/到期/落实/重算全部由服务端判定） */
export const adApi = {
  overview: () => http.get('/ad/overview'),
  directives: () => http.get('/ad/directives'),
  revisions: (id) => http.get(`/ad/directives/${id}/revisions`),
  createDirective: (data) => http.post('/ad/directives', data),
  updateDirective: (id, data) => http.post(`/ad/directives/${id}`, data),
  updateDraft: (id, data) => http.post(`/ad/revisions/${id}/draft`, data),
  newRevision: (id, data) => http.post(`/ad/directives/${id}/revisions`, data),
  publish: (id, operator) => http.post(`/ad/revisions/${id}/publish`, { operator }),
  compliance: (params) => http.get('/ad/compliance', { params }),
  complianceDetail: (id) => http.get(`/ad/compliance/${id}`),
  recompute: (id, operator) => http.post(`/ad/compliance/${id}/recompute`, { operator }),
  comply: (id, data) => http.post(`/ad/compliance/${id}/comply`, data),
  registerEvidence: (id, data) => http.post(`/ad/compliance/${id}/evidence`, data),
  uploadEvidence: (id, formData) =>
    http.post(`/ad/compliance/${id}/evidence-file`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    }),
  downloadEvidenceUrl: (evidenceId) => `/api/ad/evidence-file/${evidenceId}/download`,
  voidEvidence: (id, data) => http.post(`/ad/evidence/${id}/void`, data),
  createJob: (data) => http.post('/ad/recalc-jobs', data),
  jobs: () => http.get('/ad/recalc-jobs'),
  job: (id) => http.get(`/ad/recalc-jobs/${id}`),
  jobItems: (id) => http.get(`/ad/recalc-jobs/${id}/items`),
  resumeJob: (id) => http.post(`/ad/recalc-jobs/${id}/resume`)
}

/** 工卡扩展动作（模块七验收：已关闭重开 / 改挂） */
export const cardAdminApi = {
  reopen: (id, operator) => http.post(`/work-cards/${id}/reopen`, { operator }),
  reassign: (id, targetAircraftId, operator) =>
    http.post(`/work-cards/${id}/reassign`, { targetAircraftId, operator })
}
