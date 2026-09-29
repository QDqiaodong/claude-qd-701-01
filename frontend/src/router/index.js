import { createRouter, createWebHistory } from 'vue-router'

/**
 * 导航形态：顶部一条横向模块条（不是侧边菜单），
 * 机号上下文由 App.vue 顶栏的下拉统一切换，模块之间共享同一架飞机。
 */
export const navItems = [
  {
    key: 'hangar',
    path: '/hangar',
    icon: '🛫',
    label: '机库机位平面',
    hint: 'SVG 手绘机位栅格 + 工卡执行流水'
  },
  {
    key: 'aircraft',
    path: '/aircraft',
    icon: '✈️',
    label: '飞机台账',
    hint: '机号·机型·飞行小时·起落·适航状态'
  },
  {
    key: 'cards',
    path: '/cards',
    icon: '🧾',
    label: '定检工卡',
    hint: '待派工 → 进行中 → 待检验 → 已关闭'
  },
  {
    key: 'defects',
    path: '/defects',
    icon: '🚨',
    label: '故障保留',
    hint: 'ATA 章节·保留期限·超期标红'
  },
  {
    key: 'parts',
    path: '/parts',
    icon: '📦',
    label: '航材与借还',
    hint: '低库存预警·借出归还'
  },
  {
    key: 'release',
    path: '/release',
    icon: '🛂',
    label: '出厂放行审签',
    hint: '证据快照·三人审签·终审放行后才可出位'
  },
  {
    key: 'ad',
    path: '/ad',
    icon: '📜',
    label: '适航指令追踪',
    hint: '编号·修订版·三类阈值·落实清单·证据·批量重算'
  }
]

const routes = [
  { path: '/', redirect: '/hangar' },
  { path: '/hangar', name: 'hangar', component: () => import('../views/HangarView.vue') },
  { path: '/aircraft', name: 'aircraft', component: () => import('../views/AircraftView.vue') },
  { path: '/cards', name: 'cards', component: () => import('../views/WorkCardView.vue') },
  { path: '/defects', name: 'defects', component: () => import('../views/DefectView.vue') },
  { path: '/parts', name: 'parts', component: () => import('../views/PartView.vue') },
  { path: '/release', name: 'release', component: () => import('../views/ReleaseView.vue') },
  { path: '/ad', name: 'ad', component: () => import('../views/AdView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/hangar' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
