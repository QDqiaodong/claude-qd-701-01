<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <h3>故障保留</h3>
        <span class="panel-sub">到期日已过仍开放的整行标红；保留期限 DAY 按飞行日、HOUR 按日飞 6 小时折算</span>
        <div class="panel-extra">
          <el-checkbox v-model="onlyCurrent" label="只看当前机号" />
          <el-button size="small" @click="load">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreate">登记缺陷</el-button>
        </div>
      </div>

      <el-alert
        v-if="overdueCount > 0"
        :title="`当前有 ${overdueCount} 条保留已超期，需立即排故闭环`"
        type="error"
        show-icon
        :closable="false"
        style="margin-bottom: 12px"
      />

      <el-table :data="list" border stripe size="small" :row-class-name="rowClass">
        <el-table-column prop="defectNo" label="缺陷单号" width="130" />
        <el-table-column prop="tailNo" label="飞机" width="90" />
        <el-table-column prop="ataChapter" label="ATA" width="80" />
        <el-table-column prop="description" label="故障描述" min-width="220" show-overflow-tooltip />
        <el-table-column label="保留期限" width="120">
          <template #default="{ row }">{{ row.limitValue }} {{ row.limitUnitName }}</template>
        </el-table-column>
        <el-table-column prop="foundDate" label="发现日期" width="110" />
        <el-table-column prop="dueDate" label="到期日" width="110" />
        <el-table-column label="剩余" width="90">
          <template #default="{ row }">
            <span :style="{ color: row.daysLeft < 0 ? '#c62828' : row.daysLeft <= 3 ? '#ef6c00' : '#2e7d32' }">
              {{ row.daysLeft < 0 ? `超期 ${-row.daysLeft} 天` : `${row.daysLeft} 天` }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.overdue ? 'danger' : row.status === 'CLOSED' ? 'info' : 'warning'">
              {{ row.overdue ? '超期' : row.statusName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="reporter" label="填报人" width="120" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status !== 'CLOSED'" link type="success" @click="doClose(row)">关闭</el-button>
            <span v-else class="muted">已闭环</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="createVisible" title="登记故障保留" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="缺陷单号"><el-input v-model="form.defectNo" placeholder="如 DF-2026-0014" /></el-form-item>
        <el-form-item label="飞机">
          <el-select v-model="form.aircraftId" filterable style="width: 100%">
            <el-option v-for="a in aircraftList" :key="a.id" :label="a.tailNo + ' · ' + a.model" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="ATA 章节"><el-input v-model="form.ataChapter" placeholder="如 32-11" /></el-form-item>
        <el-form-item label="故障描述"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="保留期限">
          <el-input-number v-model="form.limitValue" :min="1" :step="1" style="width: 130px" />
          <el-select v-model="form.limitUnit" style="width: 130px; margin-left: 8px">
            <el-option label="飞行日" value="DAY" />
            <el-option label="飞行小时" value="HOUR" />
          </el-select>
        </el-form-item>
        <el-form-item label="发现日期">
          <el-date-picker v-model="form.foundDate" type="date" value-format="YYYY-MM-DD" placeholder="默认今天" />
        </el-form-item>
        <el-form-item label="填报人"><el-input v-model="form.reporter" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { defectApi } from '../api'

const mro = inject('mro')
const aircraftList = mro.aircraftList
const currentId = mro.aircraftId

const list = ref([])
const onlyCurrent = ref(false)
const createVisible = ref(false)
const form = ref({
  defectNo: '',
  aircraftId: null,
  ataChapter: '',
  description: '',
  limitUnit: 'DAY',
  limitValue: 10,
  foundDate: '',
  reporter: ''
})

const queryId = computed(() => (onlyCurrent.value ? currentId.value : null))
const overdueCount = computed(() => list.value.filter((d) => d.overdue).length)

/** 超期整行标红 */
function rowClass({ row }) {
  return row.overdue ? 'row-overdue' : ''
}

async function load() {
  list.value = await defectApi.list(queryId.value)
  await mro.refreshOverview()
}

function openCreate() {
  form.value = {
    defectNo: '',
    aircraftId: currentId.value,
    ataChapter: '',
    description: '',
    limitUnit: 'DAY',
    limitValue: 10,
    foundDate: '',
    reporter: ''
  }
  createVisible.value = true
}

async function doCreate() {
  await defectApi.create(form.value)
  ElMessage.success('缺陷已登记')
  createVisible.value = false
  await load()
}

async function doClose(row) {
  await ElMessageBox.confirm(`确认关闭缺陷 ${row.defectNo}？`, '关闭缺陷', { type: 'warning' })
  await defectApi.close(row.id)
  ElMessage.success('缺陷已关闭')
  await load()
}

watch(queryId, load)

onMounted(load)
</script>
