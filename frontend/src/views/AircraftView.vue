<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <h3>飞机台账</h3>
        <span class="panel-sub">右侧四列「工卡汇总」来自后端 @Subselect 只读视图实体，按飞机实时聚合</span>
        <div class="panel-extra">
          <el-button size="small" @click="load">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreate">新增飞机</el-button>
        </div>
      </div>

      <el-table :data="list" border stripe size="small">
        <el-table-column prop="tailNo" label="机号" width="100" />
        <el-table-column prop="model" label="机型" width="80" />
        <el-table-column prop="airline" label="所属航司" width="150" />
        <el-table-column prop="totalFh" label="总飞行小时" width="120" />
        <el-table-column prop="totalCycles" label="总起落次数" width="110" />
        <el-table-column label="适航状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType(row.airworthy)">{{ row.airworthyName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="bayNo" label="所在机位" width="110">
          <template #default="{ row }">{{ row.bayNo || '场外' }}</template>
        </el-table-column>
        <el-table-column label="工卡 总/已关" width="110">
          <template #default="{ row }">{{ row.totalCard }} / {{ row.closedCard }}</template>
        </el-table-column>
        <el-table-column prop="openCard" label="未闭环" width="90" />
        <el-table-column prop="totalHour" label="累计工时" width="100" />
        <el-table-column prop="note" label="备注" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAirworthy(row)">适航变更</el-button>
            <el-button link type="success" @click="openFlight(row)">登记飞行</el-button>
            <el-button link @click="pick(row)">设为当前机</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="createVisible" title="新增飞机" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="机号"><el-input v-model="form.tailNo" placeholder="如 B-7788" /></el-form-item>
        <el-form-item label="机型">
          <el-select v-model="form.model" placeholder="选择机型">
            <el-option v-for="m in models" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属航司"><el-input v-model="form.airline" /></el-form-item>
        <el-form-item label="总飞行小时"><el-input-number v-model="form.totalFh" :min="0" :step="100" /></el-form-item>
        <el-form-item label="总起落次数"><el-input-number v-model="form.totalCycles" :min="0" :step="100" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">建档</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="airworthyVisible" title="适航状态变更" width="420px">
      <el-form label-width="90px">
        <el-form-item label="飞机"><b>{{ actRow.tailNo }}</b></el-form-item>
        <el-form-item label="目标状态">
          <el-select v-model="airworthy" style="width: 100%">
            <el-option label="适航 AIRWORTHY" value="AIRWORTHY" />
            <el-option label="停场待修 GROUNDED" value="GROUNDED" />
            <el-option label="定检中 INSPECTION" value="INSPECTION" />
            <el-option label="封存 STORED" value="STORED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="airworthyVisible = false">取消</el-button>
        <el-button type="primary" @click="doAirworthy">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="flightVisible" title="登记飞行（累加飞行小时与起落）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="飞机"><b>{{ actRow.tailNo }}</b></el-form-item>
        <el-form-item label="飞行小时"><el-input-number v-model="flightHour" :min="0.5" :step="0.5" /></el-form-item>
        <el-form-item label="起落次数"><el-input-number v-model="cycles" :min="1" :step="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="flightVisible = false">取消</el-button>
        <el-button type="primary" @click="doFlight">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { inject, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { aircraftApi } from '../api'

const mro = inject('mro')
const currentId = mro.aircraftId

const list = ref([])
const models = ['B737', 'A320', 'A330', 'ARJ21']

const createVisible = ref(false)
const airworthyVisible = ref(false)
const flightVisible = ref(false)
const actRow = ref({})
const airworthy = ref('AIRWORTHY')
const flightHour = ref(4)
const cycles = ref(2)
const form = ref({ tailNo: '', model: 'B737', airline: '', totalFh: 0, totalCycles: 0, note: '' })

function tagType(code) {
  return { AIRWORTHY: 'success', GROUNDED: 'danger', INSPECTION: 'warning', STORED: 'info' }[code] || 'info'
}

function pick(row) {
  currentId.value = row.id
  ElMessage.success(`已切换上下文到 ${row.tailNo}`)
}

async function load() {
  list.value = await aircraftApi.list()
}

function openCreate() {
  form.value = { tailNo: '', model: 'B737', airline: '', totalFh: 0, totalCycles: 0, note: '' }
  createVisible.value = true
}

async function doCreate() {
  await aircraftApi.create(form.value)
  ElMessage.success('飞机已建档')
  createVisible.value = false
  await Promise.all([load(), mro.reloadAircraft()])
}

function openAirworthy(row) {
  actRow.value = row
  airworthy.value = row.airworthy
  airworthyVisible.value = true
}

async function doAirworthy() {
  await aircraftApi.setAirworthy(actRow.value.id, airworthy.value)
  ElMessage.success('适航状态已更新')
  airworthyVisible.value = false
  await Promise.all([load(), mro.reloadAircraft()])
}

function openFlight(row) {
  actRow.value = row
  flightHour.value = 4
  cycles.value = 2
  flightVisible.value = true
}

async function doFlight() {
  await aircraftApi.logFlight(actRow.value.id, { flightHour: flightHour.value, cycles: cycles.value })
  ElMessage.success('飞行数据已登记')
  flightVisible.value = false
  await Promise.all([load(), mro.reloadAircraft()])
}

onMounted(load)
</script>
