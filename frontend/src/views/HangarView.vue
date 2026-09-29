<template>
  <div>
    <!-- 上半：机库机位平面栅格（纯 SVG 手绘） -->
    <section class="panel">
      <div class="panel-head">
        <h3>机库机位平面</h3>
        <span class="panel-sub">点机位格子 → 安排飞机入位 / 出位；灰色空闲、蓝色占用、斜纹停用</span>
        <div class="panel-extra">
          <el-checkbox v-model="onlyFit" label="只看能停当前机型的机位" />
          <el-button size="small" type="primary" @click="openCreate">新增机位</el-button>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </div>

      <svg class="bay-svg" :viewBox="`0 0 ${svgW} ${svgH}`" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <pattern id="stripe" width="10" height="10" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <rect width="10" height="10" fill="#eceff1" />
            <line x1="0" y1="0" x2="0" y2="10" stroke="#b0bec5" stroke-width="5" />
          </pattern>
        </defs>

        <g v-for="cell in cells" :key="cell.bay.id" class="bay-cell" @click="openBay(cell.bay)">
          <rect
            class="bay-box"
            :x="cell.x"
            :y="cell.y"
            width="180"
            height="130"
            rx="8"
            :fill="fillOf(cell.bay)"
            :stroke="strokeOf(cell.bay)"
            stroke-width="2"
          />
          <text class="bay-no" :x="cell.x + 12" :y="cell.y + 22">{{ cell.bay.bayNo }}</text>
          <text class="bay-meta" :x="cell.x + 12" :y="cell.y + 38">
            {{ cell.bay.sizeClass }} 类 · {{ cell.bay.lengthM }}m
          </text>

          <!-- 占用：画一架飞机俯视轮廓（机身长条 + 后掠机翼 + 尾翼） -->
          <g v-if="cell.bay.status === 'OCCUPIED'" :transform="`translate(${cell.x + 90},${cell.y + 82})`">
            <g fill="#1565c0" stroke="#0d47a1" stroke-width="1">
              <polygon points="-6,-4 -6,6 -48,22 -52,14" />
              <polygon points="6,-4 6,6 48,22 52,14" />
              <rect x="-7" y="-44" width="14" height="88" rx="7" />
              <polygon points="-7,-36 7,-36 0,-52" />
              <polygon points="-5,32 -5,38 -26,46 -28,40" />
              <polygon points="5,32 5,38 26,46 28,40" />
              <rect x="-2" y="24" width="4" height="16" />
            </g>
            <text class="bay-tail" x="0" y="62" text-anchor="middle">{{ cell.bay.tailNo }}</text>
          </g>

          <text v-else-if="cell.bay.status === 'DISABLED'" class="bay-disabled" :x="cell.x + 90" :y="cell.y + 78" text-anchor="middle">
            停用 · 禁入位
          </text>
          <text v-else class="bay-meta" :x="cell.x + 90" :y="cell.y + 78" text-anchor="middle">
            空闲 · {{ cell.bay.fitType }}
          </text>
        </g>
      </svg>

      <div class="legend">
        <span><i style="background:#eceff1"></i>空闲</span>
        <span><i style="background:#e3f2fd"></i>占用（机号即当前飞机）</span>
        <span><i style="background:#eceff1;background-image:repeating-linear-gradient(45deg,#b0bec5 0 3px,#eceff1 3px 6px)"></i>停用</span>
        <span class="muted">共 {{ bays.length }} 个机位</span>
      </div>
    </section>

    <!-- 下半：左侧当前机概况，右侧工卡执行流水时间轴 -->
    <div class="split">
      <section class="panel">
        <div class="panel-head">
          <h3>当前飞机</h3>
          <span class="panel-sub">顶栏下拉切换机号</span>
        </div>
        <div v-if="current" class="kv-grid">
          <div class="kv"><span>机号 / 机型</span><b>{{ current.tailNo }} · {{ current.model }}</b></div>
          <div class="kv"><span>所属航司</span><b>{{ current.airline }}</b></div>
          <div class="kv"><span>适航状态</span><b>{{ current.airworthyName }}</b></div>
          <div class="kv"><span>所在机位</span><b>{{ current.bayNo || '— 场外 —' }}</b></div>
          <div class="kv"><span>总飞行小时</span><b>{{ current.totalFh }}</b></div>
          <div class="kv"><span>总起落次数</span><b>{{ current.totalCycles }}</b></div>
          <div class="kv"><span>工卡 总数 / 已关闭</span><b>{{ summary.totalCard || 0 }} / {{ summary.closedCard || 0 }}</b></div>
          <div class="kv"><span>未闭环 / 累计工时</span><b>{{ summary.openCard || 0 }} / {{ summary.totalHour || 0 }} h</b></div>
        </div>
        <el-empty v-else description="未选择飞机" />
      </section>

      <section class="panel">
        <div class="panel-head">
          <h3>工卡执行流水</h3>
          <span class="panel-sub">派工 → 开工 → 完工 → 检验 → 关闭</span>
        </div>
        <div class="timeline-wrap">
          <el-timeline v-if="nodes.length">
            <el-timeline-item
              v-for="(n, i) in nodes"
              :key="i"
              :timestamp="n.time || '待执行'"
              placement="top"
              :color="n.color"
              :hollow="!n.done"
            >
              <div class="tl-label">{{ n.label }}</div>
              <div class="tl-card">{{ n.cardNo }}</div>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="该机暂无工卡流水" />
        </div>
      </section>
    </div>

    <!-- 机位操作弹窗 -->
    <el-dialog v-model="bayVisible" :title="`机位 ${active ? active.bayNo : ''}`" width="520px">
      <template v-if="active">
        <div class="kv-grid">
          <div class="kv"><span>尺寸等级</span><b>{{ active.sizeClassName }}</b></div>
          <div class="kv"><span>机位长度</span><b>{{ active.lengthM }} m</b></div>
          <div class="kv"><span>可承载机型</span><b>{{ active.fitType }}</b></div>
          <div class="kv"><span>当前状态</span><b>{{ active.statusName }}</b></div>
          <div class="kv"><span>当前占用飞机</span><b>{{ active.tailNo || '—' }}</b></div>
          <div class="kv"><span>更新时间</span><b>{{ active.updatedAt || '—' }}</b></div>
        </div>

        <el-divider content-position="left">操作</el-divider>

        <div v-if="active.status === 'IDLE'" class="inline-form">
          <el-select v-model="occupyAircraftId" filterable placeholder="选择入位飞机" style="width: 240px">
            <el-option v-for="a in aircraftList" :key="a.id" :label="a.tailNo + ' · ' + a.model" :value="a.id" />
          </el-select>
          <el-button type="primary" @click="doOccupy">安排入位</el-button>
        </div>

        <div v-else-if="active.status === 'OCCUPIED'" class="inline-form">
          <el-button type="warning" @click="doRelease">安排出位（出厂）</el-button>
          <span class="muted">
            须先在「出厂放行审签」由质量终审通过；无有效放行 / 已失效 / 放行对不上当前维修事实都会被服务端阻断
          </span>
        </div>

        <div class="inline-form" style="margin-top: 10px">
          <el-button v-if="active.status !== 'DISABLED'" type="danger" plain @click="doStatus('DISABLED')">
            停用机位
          </el-button>
          <el-button v-else type="success" plain @click="doStatus('IDLE')">重新启用</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 新增机位弹窗 -->
    <el-dialog v-model="createVisible" title="新增机位" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="机位编号"><el-input v-model="form.bayNo" placeholder="如 BAY-B05" /></el-form-item>
        <el-form-item label="尺寸等级">
          <el-select v-model="form.sizeClass" placeholder="A / B / C">
            <el-option label="A 类（大型机位）" value="A" />
            <el-option label="B 类（中型机位）" value="B" />
            <el-option label="C 类（支线机位）" value="C" />
          </el-select>
        </el-form-item>
        <el-form-item label="机位长度"><el-input-number v-model="form.lengthM" :min="20" :max="120" :step="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { aircraftApi, bayApi, cardApi } from '../api'

const mro = inject('mro')
const aircraftList = mro.aircraftList
const currentId = mro.aircraftId

const bays = ref([])
const summaries = ref([])
const nodes = ref([])
const onlyFit = ref(false)

const bayVisible = ref(false)
const createVisible = ref(false)
const active = ref(null)
const occupyAircraftId = ref(null)
const form = ref({ bayNo: '', sizeClass: 'B', lengthM: 44 })

const current = computed(() => aircraftList.value.find((a) => a.id === currentId.value) || null)
const summary = computed(() => summaries.value.find((s) => s.aircraftId === currentId.value) || {})

const COLS = 4
const CW = 180
const CH = 130
const GAP = 12

const visibleBays = computed(() => {
  if (!onlyFit.value || !current.value) return bays.value
  return bays.value.filter((b) => (b.fitType || '').split(';').includes(current.value.model))
})

const cells = computed(() =>
  visibleBays.value.map((bay, i) => ({
    bay,
    x: (i % COLS) * (CW + GAP),
    y: Math.floor(i / COLS) * (CH + GAP)
  }))
)

const svgW = computed(() => COLS * CW + (COLS - 1) * GAP)
const svgH = computed(() => {
  const rows = Math.max(1, Math.ceil(cells.value.length / COLS))
  return rows * CH + (rows - 1) * GAP
})

function fillOf(bay) {
  if (bay.status === 'DISABLED') return 'url(#stripe)'
  if (bay.status === 'OCCUPIED') return '#e3f2fd'
  return '#eceff1'
}

function strokeOf(bay) {
  if (bay.status === 'DISABLED') return '#b0bec5'
  if (bay.status === 'OCCUPIED') return '#1565c0'
  return '#cfd8dc'
}

function openBay(bay) {
  active.value = bay
  occupyAircraftId.value = currentId.value
  bayVisible.value = true
}

function openCreate() {
  form.value = { bayNo: '', sizeClass: 'B', lengthM: 44 }
  createVisible.value = true
}

async function load() {
  bays.value = await bayApi.list()
  summaries.value = await aircraftApi.cardSummary()
  await mro.refreshOverview()
}

async function loadTimeline() {
  nodes.value = currentId.value ? await cardApi.timeline(currentId.value) : []
}

async function doOccupy() {
  if (!occupyAircraftId.value) {
    ElMessage.warning('请先选择要入位的飞机')
    return
  }
  await bayApi.occupy(active.value.id, occupyAircraftId.value)
  ElMessage.success('飞机已入位')
  bayVisible.value = false
  await Promise.all([load(), mro.reloadAircraft(), loadTimeline()])
}

async function doRelease() {
  // 阻断提示完全由服务端给出（无放行记录/已失效/不是当前维修事实，口径与放行页一致）
  await bayApi.release(active.value.id)
  ElMessage.success('飞机已凭终审放行出位')
  bayVisible.value = false
  await Promise.all([load(), mro.reloadAircraft(), loadTimeline(), mro.refreshReleaseOverview()])
}

async function doStatus(status) {
  await bayApi.setStatus(active.value.id, status)
  ElMessage.success('机位状态已更新')
  bayVisible.value = false
  await load()
}

async function doCreate() {
  await bayApi.create(form.value)
  ElMessage.success('机位已创建')
  createVisible.value = false
  await load()
}

watch(currentId, loadTimeline)

onMounted(async () => {
  await load()
  await loadTimeline()
})
</script>
