<template>
  <div class="shell">
    <!-- 顶部固定条：品牌 + 全局机号切换下拉 + 计数器 -->
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">✈</span>
        <div>
          飞机维修机库 MRO 管理平台
          <div class="brand-sub">{{ todayText }} · 机位占用 {{ overview.bayOccupied || 0 }}/{{ overview.bayTotal || 0 }}</div>
        </div>
      </div>

      <!-- 全局上下文：选中即切换整站机号 -->
      <div class="tail-picker">
        <span class="tail-label">当前机号</span>
        <el-select v-model="aircraftId" filterable placeholder="选择飞机" @change="onPickAircraft">
          <el-option v-for="a in aircraftList" :key="a.id" :label="a.tailNo + ' · ' + a.model" :value="a.id">
            <span>{{ a.tailNo }} · {{ a.model }}</span>
            <span style="float: right; font-size: 12px; opacity: 0.7">{{ a.airworthyName }}</span>
          </el-option>
        </el-select>
      </div>

      <div class="top-stats">
        <div class="stat-chip"><b>{{ overview.bayIdle || 0 }}</b><span>空闲机位</span></div>
        <div class="stat-chip"><b>{{ overview.cardInProgress || 0 }}</b><span>进行中工卡</span></div>
        <div class="stat-chip"><b>{{ overview.cardWaitInsp || 0 }}</b><span>待检验</span></div>
        <div class="stat-chip"><b>{{ overview.defectOpen || 0 }}</b><span>开放缺陷</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.defectOverdue || 0) > 0 }">
          <b>{{ overview.defectOverdue || 0 }}</b><span>超期保留</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (releaseOverview.pendingCount || 0) > 0 }">
          <b>{{ releaseOverview.pendingCount || 0 }}</b><span>放行待审</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (releaseOverview.invalidCount || 0) > 0 }">
          <b>{{ releaseOverview.invalidCount || 0 }}</b><span>放行已失效</span>
        </div>
        <div class="stat-chip"><b>{{ releaseOverview.releasedCount || 0 }}</b><span>已放行</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (adOverview.overdue || 0) > 0 }">
          <b>{{ adOverview.overdue || 0 }}</b><span>AD 超期</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (adOverview.pending || 0) > 0 }">
          <b>{{ adOverview.pending || 0 }}</b><span>AD 待核实</span>
        </div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.partLow || 0) > 0 }">
          <b>{{ overview.partLow || 0 }}</b><span>低库存件</span>
        </div>
      </div>
    </header>

    <!-- 横向模块条 -->
    <nav class="navbar">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="nav-btn"
        :class="{ 'is-on': activeKey === item.key }"
        type="button"
        :title="item.hint"
        @click="go(item.path)"
      >
        <span class="nav-ico">{{ item.icon }}</span>{{ item.label }}
      </button>
    </nav>

    <main class="stage">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { navItems } from './router'
import { aircraftApi, hangarApi, releaseApi, adApi } from './api'

/**
 * 模板自带的 main.js 只装了 Element Plus，没装 router（那份文件不动它），
 * 所以在这里把 router 装到根组件所属的 app 实例上。
 * vue-router 的 install 自带幂等保护，重复调用无副作用。
 */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

const aircraftList = ref([])
const aircraftId = ref(null)
const overview = ref({})
const releaseOverview = ref({})
const adOverview = ref({})

/**
 * 全局机号上下文：所有页面 inject('mro') 拿同一架飞机，
 * 顶栏下拉一改，机位平面、工卡流水、缺陷、借还全部跟着变。
 */
const mro = {
  aircraftId,
  aircraftList,
  overview,
  releaseOverview,
  adOverview,
  refreshOverview: loadOverview,
  refreshReleaseOverview: loadReleaseOverview,
  refreshAdOverview: loadAdOverview,
  reloadAircraft: loadAircraft
}
provide('mro', mro)

const todayText = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const activeKey = computed(() => {
  const hit = navItems.find((item) => route.path.startsWith(item.path))
  return hit ? hit.key : 'hangar'
})

function go(path) {
  nav.push(path)
}

function onPickAircraft(id) {
  localStorage.setItem('mro-tail-id', String(id == null ? '' : id))
  loadReleaseOverview()
}

async function loadAircraft() {
  aircraftList.value = await aircraftApi.list()
  if (!aircraftId.value) {
    const saved = Number(localStorage.getItem('mro-tail-id'))
    const hit = aircraftList.value.find((a) => a.id === saved)
    aircraftId.value = hit ? hit.id : (aircraftList.value[0] ? aircraftList.value[0].id : null)
  }
}

async function loadOverview() {
  overview.value = await hangarApi.overview()
}

async function loadReleaseOverview() {
  releaseOverview.value = await releaseApi.overview(aircraftId.value)
}

async function loadAdOverview() {
  adOverview.value = await adApi.overview()
}

onMounted(async () => {
  await loadAircraft()
  await Promise.all([loadOverview(), loadReleaseOverview(), loadAdOverview()])
})

// 从其他模块回到本页时自动刷新 AD 计数（如登记飞行、工卡变化后）
router.afterEach((to) => {
  if (to.path === '/ad') {
    loadAdOverview()
  }
})
</script>
