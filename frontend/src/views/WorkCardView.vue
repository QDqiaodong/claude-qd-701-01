<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <h3>定检工卡</h3>
        <span class="panel-sub">状态机：待派工 → 进行中 → 待检验 → 已关闭，待派工/进行中可取消</span>
        <div class="panel-extra">
          <el-checkbox v-model="onlyCurrent" label="只看当前机号" />
          <el-button size="small" @click="load">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreate">开具工卡</el-button>
        </div>
      </div>

      <el-table :data="list" border stripe size="small">
        <el-table-column prop="cardNo" label="工卡号" width="130" />
        <el-table-column prop="tailNo" label="飞机" width="90" />
        <el-table-column label="检类" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.checkType === 'C_CHECK' ? 'warning' : 'primary'">
              {{ row.checkTypeName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="工作内容" min-width="180" show-overflow-tooltip />
        <el-table-column prop="plannedHour" label="计划工时" width="100" />
        <el-table-column prop="actualHour" label="实际工时" width="100" />
        <el-table-column label="开工 / 完工" width="170">
          <template #default="{ row }">{{ fmt(row.startTime) }} → {{ fmt(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="executor" label="执行人" width="90" />
        <el-table-column prop="inspector" label="检验员" width="90" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'ASSIGNED'" link type="primary" @click="doStart(row)">开工</el-button>
            <el-button v-if="row.status === 'IN_PROGRESS'" link type="warning" @click="doFinish(row)">报完工</el-button>
            <el-button v-if="row.status === 'WAIT_INSP'" link type="success" @click="doInspect(row)">检验关闭</el-button>
            <el-button v-if="row.status === 'ASSIGNED' || row.status === 'IN_PROGRESS'" link type="danger" @click="doCancel(row)">取消</el-button>
            <span v-if="row.status === 'CLOSED' || row.status === 'CANCELED'" class="muted">终态</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="createVisible" title="开具定检工卡" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="工卡号"><el-input v-model="form.cardNo" placeholder="如 WC-2026-0016" /></el-form-item>
        <el-form-item label="飞机">
          <el-select v-model="form.aircraftId" filterable style="width: 100%">
            <el-option v-for="a in aircraftList" :key="a.id" :label="a.tailNo + ' · ' + a.model" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="检类">
          <el-select v-model="form.checkType">
            <el-option label="A检" value="A_CHECK" />
            <el-option label="C检" value="C_CHECK" />
            <el-option label="例行" value="ROUTINE" />
            <el-option label="专项" value="SPECIAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="工作内容"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="计划工时"><el-input-number v-model="form.plannedHour" :min="0.5" :step="0.5" /></el-form-item>
        <el-form-item label="执行人"><el-input v-model="form.executor" placeholder="可留空，待派工" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">开卡</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cardApi } from '../api'

const mro = inject('mro')
const aircraftList = mro.aircraftList
const currentId = mro.aircraftId

const list = ref([])
const onlyCurrent = ref(false)
const createVisible = ref(false)
const form = ref({ cardNo: '', aircraftId: null, checkType: 'ROUTINE', title: '', plannedHour: 4, executor: '' })

const queryId = computed(() => (onlyCurrent.value ? currentId.value : null))

function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

function statusType(code) {
  return {
    ASSIGNED: 'info',
    IN_PROGRESS: 'primary',
    WAIT_INSP: 'warning',
    CLOSED: 'success',
    CANCELED: 'danger'
  }[code] || 'info'
}

async function load() {
  list.value = await cardApi.list(queryId.value)
}

function openCreate() {
  form.value = { cardNo: '', aircraftId: currentId.value, checkType: 'ROUTINE', title: '', plannedHour: 4, executor: '' }
  createVisible.value = true
}

async function doCreate() {
  await cardApi.create(form.value)
  ElMessage.success('工卡已开具')
  createVisible.value = false
  await load()
}

async function doStart(row) {
  const { value } = await ElMessageBox.prompt('开工执行人', '开工', { inputValue: row.executor || '' })
  if (!value) return
  await cardApi.start(row.id, value)
  ElMessage.success('工卡已开工')
  await load()
}

async function doFinish(row) {
  const { value } = await ElMessageBox.prompt('实测工时（小时）', '报完工', { inputValue: String(row.plannedHour) })
  await cardApi.finish(row.id, Number(value))
  ElMessage.success('已报完工，等待检验')
  await load()
}

async function doInspect(row) {
  const { value } = await ElMessageBox.prompt('检验员（不得与执行人相同）', '检验关闭', { inputValue: '' })
  if (!value) return
  await cardApi.inspect(row.id, value)
  ElMessage.success('工卡检验通过并关闭')
  await load()
}

async function doCancel(row) {
  await ElMessageBox.confirm(`确认取消工卡 ${row.cardNo}？`, '取消工卡', { type: 'warning' })
  await cardApi.cancel(row.id)
  ElMessage.success('工卡已取消')
  await load()
}

watch([queryId], load)

onMounted(load)
</script>
