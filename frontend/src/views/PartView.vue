<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <h3>航材库存</h3>
        <span class="panel-sub">库存低于最低库存的行标黄预警，库存为 0 时禁止借出</span>
        <div class="panel-extra">
          <el-tag v-if="lowCount > 0" type="danger" size="small">{{ lowCount }} 件低于最低库存</el-tag>
          <el-button size="small" @click="loadAll">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreate">新增航材件</el-button>
        </div>
      </div>

      <el-table :data="parts" border stripe size="small" :row-class-name="partRowClass">
        <el-table-column prop="partNo" label="件号" width="130" />
        <el-table-column prop="name" label="名称" width="150" />
        <el-table-column prop="spec" label="规格" min-width="160" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column label="库存 / 最低" width="130">
          <template #default="{ row }">
            <span :style="{ color: row.lowStock ? '#c62828' : '#1c2b3a', fontWeight: row.lowStock ? 700 : 400 }">
              {{ row.stockQty }} / {{ row.minQty }}
            </span>
            <el-tag v-if="row.lowStock" size="small" type="danger" style="margin-left: 6px">预警</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="unitPrice" label="单价(元)" width="110" />
        <el-table-column prop="stockValue" label="库存金额(元)" width="130" />
        <el-table-column prop="location" label="库位" width="100" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.stockQty <= 0" @click="openLoan(row)">借出</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h3>借出 / 归还记录</h3>
        <span class="panel-sub">未归还可分次还，归还数量不得大于借出数量</span>
      </div>
      <el-table :data="loans" border stripe size="small">
        <el-table-column prop="loanNo" label="借还单号" width="130" />
        <el-table-column label="航材" min-width="200">
          <template #default="{ row }">{{ row.partNo }} · {{ row.partName }}</template>
        </el-table-column>
        <el-table-column prop="borrower" label="领用人" width="100" />
        <el-table-column prop="purpose" label="用途" min-width="160" show-overflow-tooltip />
        <el-table-column prop="quantity" label="数量" width="90">
          <template #default="{ row }">{{ row.quantity }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column label="借出时间" width="160">
          <template #default="{ row }">{{ fmt(row.loanTime) }}</template>
        </el-table-column>
        <el-table-column label="归还时间" width="160">
          <template #default="{ row }">{{ fmt(row.returnTime) }}</template>
        </el-table-column>
        <el-table-column prop="handler" label="经办人" width="110" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="!row.returned" link type="success" @click="doReturn(row)">归还</el-button>
            <el-tag v-else size="small" type="info">已归还</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="loanVisible" :title="`借出 ${actRow.partNo || ''}`" width="460px">
      <el-form label-width="90px">
        <el-form-item label="航材"><b>{{ actRow.partNo }} · {{ actRow.name }}</b></el-form-item>
        <el-form-item label="现有库存">{{ actRow.stockQty }} {{ actRow.unit }}</el-form-item>
        <el-form-item label="领用人"><el-input v-model="loanForm.borrower" /></el-form-item>
        <el-form-item label="用途"><el-input v-model="loanForm.purpose" placeholder="关联工卡 / 飞机" /></el-form-item>
        <el-form-item label="数量"><el-input-number v-model="loanForm.quantity" :min="1" :max="actRow.stockQty" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="loanVisible = false">取消</el-button>
        <el-button type="primary" @click="doLoan">借出</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新增航材件" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="件号"><el-input v-model="form.partNo" placeholder="如 PN-3200-005" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="规格"><el-input v-model="form.spec" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="form.unit" style="width: 120px" /></el-form-item>
        <el-form-item label="库存数量"><el-input-number v-model="form.stockQty" :min="0" /></el-form-item>
        <el-form-item label="最低库存"><el-input-number v-model="form.minQty" :min="0" /></el-form-item>
        <el-form-item label="单价"><el-input-number v-model="form.unitPrice" :min="0" :step="100" /></el-form-item>
        <el-form-item label="库位"><el-input v-model="form.location" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { partApi } from '../api'

const parts = ref([])
const loans = ref([])

const loanVisible = ref(false)
const createVisible = ref(false)
const actRow = ref({})
const loanForm = ref({ borrower: '', purpose: '', quantity: 1 })
const form = ref({ partNo: '', name: '', spec: '', unit: '件', stockQty: 0, minQty: 0, unitPrice: 0, location: '' })

const lowCount = computed(() => parts.value.filter((p) => p.lowStock).length)

function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

function partRowClass({ row }) {
  return row.lowStock ? 'row-low' : ''
}

async function loadAll() {
  parts.value = await partApi.list()
  loans.value = await partApi.loans()
}

function openLoan(row) {
  actRow.value = row
  loanForm.value = { borrower: '', purpose: '', quantity: 1 }
  loanVisible.value = true
}

async function doLoan() {
  await partApi.loanOut({
    partId: actRow.value.id,
    borrower: loanForm.value.borrower,
    purpose: loanForm.value.purpose,
    quantity: loanForm.value.quantity
  })
  ElMessage.success('航材已借出')
  loanVisible.value = false
  await loadAll()
}

async function doReturn(row) {
  const { value } = await ElMessageBox.prompt(`归还数量（借出 ${row.quantity} ${row.unit}）`, '归还航材', {
    inputValue: String(row.quantity)
  })
  await partApi.returnLoan(row.id, Number(value))
  ElMessage.success('航材已归还')
  await loadAll()
}

function openCreate() {
  form.value = { partNo: '', name: '', spec: '', unit: '件', stockQty: 0, minQty: 0, unitPrice: 0, location: '' }
  createVisible.value = true
}

async function doCreate() {
  await partApi.create(form.value)
  ElMessage.success('航材件已建档')
  createVisible.value = false
  await loadAll()
}

onMounted(loadAll)
</script>
