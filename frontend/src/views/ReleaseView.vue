<template>
  <div>
    <!-- 顶部总览：待审 / 已失效 / 已放行（与顶栏同源，服务端事实） -->
    <section class="panel">
      <div class="panel-head">
        <h3>出厂放行审签 · {{ current ? current.tailNo + ' · ' + current.model : '未选择飞机' }}</h3>
        <span class="panel-sub">证据快照不可变 · 三人审签互不兼任 · 终审放行后才允许出位</span>
        <div class="panel-extra">
          <el-button size="small" @click="loadAll">刷新</el-button>
        </div>
      </div>
      <div class="rel-overview">
        <div class="rel-count is-pending"><b>{{ relOverview.pendingCount || 0 }}</b><span>待审（待机械/待终审）</span></div>
        <div class="rel-count is-invalid"><b>{{ relOverview.invalidCount || 0 }}</b><span>已失效</span></div>
        <div class="rel-count is-released"><b>{{ relOverview.releasedCount || 0 }}</b><span>已放行</span></div>
        <div class="rel-count"><b>{{ relOverview.draftCount || 0 }}</b><span>草稿</span></div>
        <div class="rel-count"><b>{{ relOverview.rejectedCount || 0 }}</b><span>已驳回</span></div>
        <div class="rel-gate" :class="gate.allowed ? 'is-ok' : 'is-block'">
          <div class="rel-gate-title">当前飞机放行门禁（服务端判定）</div>
          <div class="rel-gate-msg">{{ gate.message || '读取门禁中…' }}</div>
        </div>
      </div>
    </section>

    <div class="split" style="grid-template-columns: minmax(300px, 0.9fr) minmax(420px, 1.1fr)">
      <!-- 左：当前飞机的放行总览 + 发起入口 -->
      <section class="panel">
        <div class="panel-head"><h3>当前飞机放行总览</h3></div>
        <div v-if="current" class="kv-grid">
          <div class="kv"><span>机号 / 机型</span><b>{{ current.tailNo }} · {{ current.model }}</b></div>
          <div class="kv"><span>适航状态</span>
            <b :style="{ color: gate.airworthyOk ? 'var(--mro-ok)' : 'var(--mro-danger)' }">{{ gate.airworthyName || current.airworthyName }}</b>
          </div>
          <div class="kv"><span>所在机位</span><b>{{ gate.bayNo || '场外' }}{{ gate.occupied ? '（占用中）' : '' }}</b></div>
          <div class="kv"><span>未闭环工卡</span>
            <b :style="{ color: gate.openCardCount ? 'var(--mro-danger)' : 'var(--mro-ok)' }">{{ gate.openCardCount ?? '-' }} 张</b>
          </div>
          <div class="kv"><span>开放故障（未超期）</span><b>{{ gate.openDefectCount ?? '-' }} 条</b></div>
          <div class="kv"><span>超期故障</span>
            <b :style="{ color: gate.overdueDefectCount ? 'var(--mro-danger)' : 'var(--mro-ok)' }">{{ gate.overdueDefectCount ?? '-' }} 条</b>
          </div>
        </div>

        <el-divider content-position="left">发起 / 进行中</el-divider>

        <div v-if="activeOrder" class="active-order" :class="statusTone(activeOrder.status)">
          <div class="ao-head">
            <el-tag :type="statusTag(activeOrder.status)">{{ activeOrder.statusName }}</el-tag>
            <b>{{ activeOrder.releaseNo }}</b>
            <span class="muted">发起 {{ fmt(activeOrder.createdAt) }}</span>
          </div>
          <div class="ao-reason" v-if="activeOrder.status === 'INVALID'">
            失效原因：{{ activeOrder.invalidReason }}（已有签字保留备查，不可沿用，须重新发起）
          </div>
          <div class="ao-reason" v-else-if="activeOrder.status === 'REJECTED'">
            驳回原因：{{ activeOrder.rejectReason }}（修订并重新提交将生成新版本快照）
          </div>
          <el-button size="small" type="primary" style="margin-top: 8px" @click="openDetail(activeOrder.id)">
            打开审签 / 待办操作
          </el-button>
        </div>

        <div v-else class="inline-form" style="margin: 6px 0">
          <el-input v-model="initiator" placeholder="发起人姓名（机械员）" style="width: 200px" />
          <el-button type="primary" :disabled="!gate.allowed" @click="doCreate">
            发起出厂放行（生成证据快照）
          </el-button>
        </div>
        <div v-if="current && !gate.allowed" class="block-hint">
          <div v-for="(b, i) in gate.blockers || []" :key="i">⛔ {{ b }}</div>
        </div>

        <el-divider content-position="left">验收演示数据（直接点）</el-divider>
        <div class="demo-list">
          <el-button link type="primary" @click="pickAndOpen(13, null)">[1] B-9001 正常三人审签</el-button>
          <el-button link type="primary" @click="pickAndOpen(14, null)">[2] B-9002 开放故障带依据放行</el-button>
          <el-button link type="warning" @click="pickAndOpen(15, 3)">[3] B-9003 工卡变化已失效</el-button>
          <el-button link type="warning" @click="pickAndOpen(16, 4)">[4] B-9004 两人同时终审</el-button>
          <el-button link type="success" @click="pickAndOpen(17, 5)">[5] B-9005 已放行→去机位出位</el-button>
          <el-button link @click="pickAndOpen(18, null)">[6] B-9006 无放行，出位被阻断</el-button>
        </div>
      </section>

      <!-- 右：该机审签记录 -->
      <section class="panel">
        <div class="panel-head">
          <h3>审签记录</h3>
          <span class="panel-sub">切换顶栏机号自动刷新；状态以服务端为准</span>
        </div>
        <el-table :data="orderList" border stripe size="small">
          <el-table-column prop="releaseNo" label="审单号" width="130" />
          <el-table-column label="状态" width="105">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(row.status)">{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="initiator" label="发起人" width="90" />
          <el-table-column prop="mechSigner" label="机械放行" width="90">
            <template #default="{ row }">{{ row.mechSigner || '—' }}</template>
          </el-table-column>
          <el-table-column prop="qaSigner" label="质量终审" width="90">
            <template #default="{ row }">{{ row.qaSigner || '—' }}</template>
          </el-table-column>
          <el-table-column label="快照" width="70">
            <template #default="{ row }">v{{ row.snapshotVersion || 0 }}</template>
          </el-table-column>
          <el-table-column label="开放/超期故障" width="100">
            <template #default="{ row }">
              <span :style="{ color: row.overdueDefectCount ? 'var(--mro-danger)' : '' }">
                {{ row.openDefectCount }} / {{ row.overdueDefectCount }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <!-- ================= 审签详情抽屉 ================= -->
    <el-drawer v-model="detailVisible" size="72%" :title="detail ? detail.releaseNo + ' · ' + detail.tailNo : ''">
      <template v-if="detail">
        <!-- 状态条 + 待办操作 -->
        <div class="detail-status" :class="statusTone(detail.status)">
          <div>
            <el-tag :type="statusTag(detail.status)" size="large">{{ detail.statusName }}</el-tag>
            <span class="muted" style="margin-left: 10px">
              发起 {{ detail.initiator }} · 机械 {{ detail.mechSigner || '未签' }} · 终审 {{ detail.qaSigner || '未签' }}
            </span>
          </div>
          <div class="todo-ops">
            <!-- 草稿 / 已驳回：发起人提交 -->
            <template v-if="detail.editable">
              <el-input v-model="operatorName" placeholder="操作人（须为发起人）" style="width: 190px" size="small" />
              <el-button size="small" type="primary" @click="doSubmit">提交（生成新版本快照）</el-button>
            </template>
            <!-- 待机械放行 -->
            <template v-if="detail.status === 'WAIT_MECH'">
              <el-input v-model="mechName" placeholder="机械放行人（≠发起人）" style="width: 200px" size="small" />
              <el-button size="small" type="warning" plain @click="openReject('MECH')">驳回</el-button>
              <el-button size="small" type="primary" @click="doMechSign">机械放行</el-button>
            </template>
            <!-- 待质量终审 -->
            <template v-if="detail.status === 'WAIT_QA'">
              <el-input v-model="qaName" placeholder="质量终审人（≠前两人）" style="width: 200px" size="small" />
              <el-button size="small" type="warning" plain @click="openReject('QA')">驳回</el-button>
              <el-button size="small" type="success" @click="doQaApprove">质量终审通过</el-button>
              <el-button size="small" @click="doConcurrentApprove">并发模拟：两个终审人同时点</el-button>
            </template>
            <!-- 已放行 -->
            <template v-if="detail.status === 'RELEASED'">
              <el-alert type="success" :closable="false" show-icon style="padding: 4px 10px"
                title="终审已放行：请到「机库机位平面」对该机所在机位执行出位（无此记录出位会被统一阻断）" />
            </template>
            <!-- 已失效 -->
            <template v-if="detail.status === 'INVALID'">
              <el-alert type="error" :closable="false" show-icon style="padding: 4px 10px"
                :title="'已失效：' + detail.invalidReason + '。必须重新发起放行。'" />
            </template>
          </div>
        </div>

        <el-alert v-if="detail.rejectReason" type="warning" :closable="false" show-icon style="margin: 10px 0"
          :title="'最近驳回原因：' + detail.rejectReason" />

        <!-- 当前事实门禁（刷新即与服务端一致） -->
        <div class="panel" style="margin-bottom: 12px">
          <div class="panel-head"><h3>当前维修事实（服务端实时）</h3>
            <span class="panel-sub">与下方最新快照不一致时，审签立即失效 / 终审不放行</span></div>
          <div class="kv-grid">
            <div class="kv"><span>适航状态</span><b>{{ detail.currentGate.airworthyName }}</b></div>
            <div class="kv"><span>所在机位</span><b>{{ detail.currentGate.bayNo || '场外' }} · {{ detail.currentGate.bayStatusName }}</b></div>
            <div class="kv"><span>未闭环工卡</span><b>{{ detail.currentGate.openCardCount }}</b></div>
            <div class="kv"><span>开放 / 超期故障</span><b>{{ detail.currentGate.openDefectCount }} / {{ detail.currentGate.overdueDefectCount }}</b></div>
          </div>
        </div>

        <!-- 保留依据（草稿/已驳回逐条填写；待终审逐条确认） -->
        <div v-for="snap in detail.snapshots" :key="snap.id">
          <div v-if="snap.latest && snap.defects.some((d) => !d.overdue)">
            <div class="panel" style="margin-bottom: 12px">
              <div class="panel-head">
                <h3>开放故障保留依据（快照 v{{ snap.versionNo }}）</h3>
                <span class="panel-sub">
                  {{ detail.editable ? '发起人逐条填写后才能提交' : '质量终审须逐条确认后才能通过' }}
                </span>
              </div>
              <div v-for="d in snap.defects.filter((x) => !x.overdue)" :key="d.itemId" class="retain-row">
                <div class="retain-meta">
                  <el-tag size="small" type="warning">{{ d.defectNo }}</el-tag>
                  {{ d.ataChapter }} · {{ d.description }}
                  <el-tag v-if="d.qaConfirmed" size="small" type="success">终审已确认</el-tag>
                  <el-tag v-else-if="!detail.editable && detail.status === 'WAIT_QA'" size="small" type="info">待终审确认</el-tag>
                </div>
                <div class="inline-form">
                  <el-input v-model="basisDraft[d.itemId]" :placeholder="'保留依据（MEL 条款 / 工程评估单号）'" size="small" style="width: 360px"
                            :disabled="!detail.editable" />
                  <el-button v-if="detail.editable" size="small" @click="doSaveBasis(d)">保存依据</el-button>
                  <template v-if="!detail.editable && detail.status === 'WAIT_QA'">
                    <el-button size="small" type="success" plain @click="doConfirmDefect(d, true)">确认保留成立</el-button>
                    <el-button size="small" type="danger" plain @click="doConfirmDefect(d, false)">撤回确认</el-button>
                  </template>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 证据快照差异 -->
        <div class="panel" style="margin-bottom: 12px">
          <div class="panel-head">
            <h3>证据快照与差异（不可变）</h3>
            <span class="panel-sub">历史版本永久保留；差异列为"快照当时 → 当前事实"，上游变化不会改写快照</span>
          </div>
          <el-collapse v-model="activeSnapTabs">
            <el-collapse-item v-for="snap in [...detail.snapshots].reverse()" :key="snap.id"
                              :name="snap.id" :title="`v${snap.versionNo} · ${snap.snapshotKindName} · ${fmt(snap.createdAt)}`">
              <div class="snap-head-row">
                <el-tag size="small">{{ snap.airworthyName }}</el-tag>
                <el-tag size="small" type="info">{{ snap.bayNo || '场外' }} · {{ snap.bayStatusName }}</el-tag>
                <el-tag size="small" :type="snap.openCardCount ? 'danger' : 'success'">未闭环工卡 {{ snap.openCardCount }}</el-tag>
                <el-tag size="small" :type="snap.openDefectCount ? 'warning' : 'success'">开放故障 {{ snap.openDefectCount }}</el-tag>
                <el-tag size="small" :type="snap.overdueDefectCount ? 'danger' : 'success'">超期 {{ snap.overdueDefectCount }}</el-tag>
              </div>

              <el-table :data="snap.cards" border size="small" style="margin-top: 8px"
                        :row-class-name="diffRowClass">
                <el-table-column prop="cardNo" label="工卡号" width="130" />
                <el-table-column prop="title" label="内容" min-width="160" show-overflow-tooltip />
                <el-table-column label="快照时状态" width="110">
                  <template #default="{ row }">{{ row.statusName }}{{ row.closed ? '✅' : '' }}</template>
                </el-table-column>
                <el-table-column prop="executor" label="执行人" width="90" />
                <el-table-column prop="inspector" label="检验员" width="90" />
                <el-table-column label="当前事实" width="120">
                  <template #default="{ row }">
                    <span :style="{ color: row.changed ? 'var(--mro-danger)' : '' }">
                      {{ row.currentStatusName }}<span v-if="row.changeKind === 'NEW'">（新增）</span>
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="差异" width="110">
                  <template #default="{ row }">
                    <el-tag v-if="row.changed" size="small" type="danger">{{ changeText(row.changeKind) }}</el-tag>
                    <span v-else class="muted">一致</span>
                  </template>
                </el-table-column>
              </el-table>

              <el-table :data="snap.defects" border size="small" style="margin-top: 8px"
                        :row-class-name="diffRowClass">
                <el-table-column prop="defectNo" label="缺陷单号" width="130" />
                <el-table-column prop="description" label="描述" min-width="170" show-overflow-tooltip />
                <el-table-column prop="dueDate" label="到期日" width="105" />
                <el-table-column label="快照时" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.overdue ? 'danger' : 'warning'">{{ row.overdue ? '超期' : '开放' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="保留依据 / 终审" min-width="180">
                  <template #default="{ row }">
                    <div v-if="!row.overdue">
                      <div>{{ row.retainBasis || '（未填写）' }}</div>
                      <el-tag size="small" :type="row.qaConfirmed ? 'success' : 'info'">
                        {{ row.qaConfirmed ? '终审已确认' : '终审未确认' }}
                      </el-tag>
                    </div>
                    <span v-else class="muted">超期故障不得放行</span>
                  </template>
                </el-table-column>
                <el-table-column label="当前事实" width="110">
                  <template #default="{ row }">
                    <span :style="{ color: row.changed ? 'var(--mro-danger)' : '' }">{{ row.currentStatusName }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="差异" width="110">
                  <template #default="{ row }">
                    <el-tag v-if="row.changed" size="small" type="danger">{{ changeText(row.changeKind) }}</el-tag>
                    <span v-else class="muted">一致</span>
                  </template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
        </div>

        <!-- 完整审计时间线 -->
        <div class="panel">
          <div class="panel-head"><h3>完整审计时间线</h3><span class="panel-sub">append-only，任何人不可改写</span></div>
          <el-timeline>
            <el-timeline-item v-for="e in [...detail.events].reverse()" :key="e.id"
                              :timestamp="fmt(e.eventAt)" placement="top" :color="eventColor(e.eventType)">
              <div><el-tag size="small">{{ e.eventName }}</el-tag>
                <b style="margin-left: 8px">{{ e.operator }}</b>
                <span v-if="e.snapshotVersion" class="muted">（快照 v{{ e.snapshotVersion }}）</span>
              </div>
              <div class="muted" style="margin-top: 2px">{{ e.detail }}</div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </template>
    </el-drawer>

    <!-- 驳回弹窗 -->
    <el-dialog v-model="rejectVisible" title="驳回（必须填写原因）" width="460px">
      <el-form label-width="90px">
        <el-form-item label="驳回环节"><b>{{ rejectStage === 'QA' ? '质量终审驳回' : '机械放行驳回' }}</b></el-form-item>
        <el-form-item label="签字人">
          <el-input v-model="rejectSigner" :placeholder="rejectStage === 'QA' ? '质量终审人（≠前两人）' : '机械放行人（≠发起人）'" />
        </el-form-item>
        <el-form-item label="驳回原因"><el-input v-model="rejectReason" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="warning" @click="doReject">确认驳回，退回修订</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { releaseApi } from '../api'

const mro = inject('mro')
const aircraftList = mro.aircraftList
const currentId = mro.aircraftId

const current = computed(() => aircraftList.value.find((a) => a.id === currentId.value) || null)

const relOverview = ref({})
const gate = ref({})
const orderList = ref([])
const initiator = ref('')

const detailVisible = ref(false)
const detail = ref(null)
const activeSnapTabs = ref([])
const operatorName = ref('')
const mechName = ref('')
const qaName = ref('')
const basisDraft = reactive({})

const rejectVisible = ref(false)
const rejectStage = ref('MECH')
const rejectSigner = ref('')
const rejectReason = ref('')

/** 进行中（有效占位）的审签：列表里 activeAircraftId 不为空的那条（列表接口已按时间倒序） */
const activeOrder = computed(() =>
  orderList.value.find((o) => ['DRAFT', 'WAIT_MECH', 'WAIT_QA', 'RELEASED', 'REJECTED'].includes(o.status)) || null)

function statusTag(s) {
  return { DRAFT: 'info', WAIT_MECH: 'warning', WAIT_QA: 'warning', RELEASED: 'success', REJECTED: 'danger', INVALID: 'danger' }[s] || 'info'
}
function statusTone(s) {
  return { DRAFT: 'tone-draft', WAIT_MECH: 'tone-wait', WAIT_QA: 'tone-wait', RELEASED: 'tone-released', REJECTED: 'tone-reject', INVALID: 'tone-invalid' }[s] || ''
}
function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '—'
}
function changeText(k) {
  return { STATUS: '状态变化', UPDATED: '被更新', NEW: '新增', GONE: '已消失', CLOSED: '已闭环', BECAME_OVERDUE: '变为超期', OVERDUE_CLEARED: '超期解除' }[k] || '变化'
}
function diffRowClass({ row }) {
  return row.changed ? 'row-overdue' : ''
}
function eventColor(t) {
  return { CREATE: '#90a4ae', SUBMIT: '#1565c0', MECH_SIGN: '#0288d1', QA_SIGN: '#2e7d32', REJECT: '#ef6c00', INVALIDATE: '#c62828', DEPART: '#2e7d32' }[t] || '#90a4ae'
}

async function loadAll() {
  if (!currentId.value) return
  const [g, list, ov] = await Promise.all([
    releaseApi.gate(currentId.value),
    releaseApi.list(currentId.value),
    mro.refreshReleaseOverview()
  ])
  gate.value = g
  orderList.value = list
  relOverview.value = ov || mro.releaseOverview
}

async function openDetail(id) {
  detail.value = await releaseApi.detail(id)
  activeSnapTabs.value = detail.value.snapshots.filter((s) => s.latest).map((s) => s.id)
  operatorName.value = detail.value.initiator || ''
  // 演示用默认签字人（三人互不相同），可自行修改
  if (!mechName.value) mechName.value = '罗建军'
  if (!qaName.value) qaName.value = '周国平'
  basisDraftFromDetail()
  detailVisible.value = true
}

function basisDraftFromDetail() {
  Object.keys(basisDraft).forEach((k) => delete basisDraft[k])
  const latest = detail.value.snapshots.find((s) => s.latest)
  if (latest) {
    latest.defects.filter((d) => !d.overdue).forEach((d) => { basisDraft[d.itemId] = d.retainBasis || '' })
  }
}

async function refreshDetail(close = false) {
  await Promise.all([loadAll(), mro.reloadAircraft(), mro.refreshOverview()])
  if (close) {
    detailVisible.value = false
    return
  }
  if (detail.value) {
    detail.value = await releaseApi.detail(detail.value.id)
    basisDraftFromDetail()
  }
}

async function doCreate() {
  if (!initiator.value.trim()) {
    ElMessage.warning('请填写发起人姓名')
    return
  }
  const d = await releaseApi.create({ aircraftId: currentId.value, initiator: initiator.value.trim() })
  ElMessage.success('已发起，v1 证据快照已生成')
  await loadAll()
  await openDetail(d.id)
}

async function doSaveBasis(d) {
  await releaseApi.saveRetainBasis(detail.value.id, {
    snapshotDefectId: d.itemId,
    retainBasis: (basisDraft[d.itemId] || '').trim(),
    operator: operatorName.value.trim()
  })
  ElMessage.success('保留依据已保存（终审确认会在提交后进行）')
  await refreshDetail()
}

async function doSubmit() {
  const r = await releaseApi.submit(detail.value.id, operatorName.value.trim())
  ElMessage.success(r.message || '已提交')
  await refreshDetail()
}

async function doMechSign() {
  const r = await releaseApi.mechSign(detail.value.id, mechName.value.trim())
  ElMessage.success(r.message || '机械放行已签字')
  mechName.value = ''
  await refreshDetail()
}

async function doConfirmDefect(d, confirmed) {
  await releaseApi.confirmDefect(detail.value.id, {
    snapshotDefectId: d.itemId,
    signer: qaName.value.trim(),
    confirmed
  })
  ElMessage.success(confirmed ? '已确认该条保留依据' : '已撤回确认')
  await refreshDetail()
}

async function doQaApprove() {
  const r = await releaseApi.qaApprove(detail.value.id, qaName.value.trim())
  ElMessage.success(r.message || '终审通过')
  qaName.value = ''
  await refreshDetail()
}

/**
 * 两人同时终审：用 Promise.all 真并发打两个请求（不同终审人）。
 * 服务端行锁串行化：只有一个成功，另一个拿到明确的当前状态冲突提示；
 * 同一人重复点则幂等返回同一结果。
 */
async function doConcurrentApprove() {
  if (!qaName.value.trim()) {
    qaName.value = '周国平'
  }
  const id = detail.value.id
  const a = qaName.value.trim()
  const b = a === '周国平' ? '何永清' : '周国平'
  ElMessage.info(`并发提交两个终审：${a} 与 ${b}（服务端仲裁，只有一个成功）`)
  const results = await Promise.allSettled([
    releaseApi.qaApprove(id, a),
    releaseApi.qaApprove(id, b)
  ])
  const ok = results.filter((r) => r.status === 'fulfilled').length
  const fail = results.filter((r) => r.status === 'rejected').length
  ElMessage.success(`仲裁完成：${ok} 个成功，${fail} 个被拒（被拒方已收到当前状态提示）`)
  await refreshDetail()
}

function openReject(stage) {
  rejectStage.value = stage
  rejectSigner.value = stage === 'QA' ? qaName.value.trim() : mechName.value.trim()
  rejectReason.value = ''
  rejectVisible.value = true
}

async function doReject() {
  await releaseApi.reject(detail.value.id, { signer: rejectSigner.value.trim(), reason: rejectReason.value.trim() })
  ElMessage.success('已驳回，回到可修订状态')
  rejectVisible.value = false
  await refreshDetail()
}

async function pickAndOpen(aircraftId, orderId) {
  currentId.value = aircraftId
  localStorage.setItem('mro-tail-id', String(aircraftId))
  await loadAll()
  if (orderId) {
    await openDetail(orderId)
  } else {
    detailVisible.value = false
  }
}

watch(currentId, loadAll)
onMounted(loadAll)
</script>
