import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/views/ChatView.vue'),
    },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('@/views/DashboardView.vue'),
    },
    {
      path: '/ingestion',
      name: 'ingestion',
      component: () => import('@/views/IngestionView.vue'),
    },
    {
      path: '/evaluation',
      name: 'evaluation',
      component: () => import('@/views/EvaluationView.vue'),
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
    },
    {
      path: '/spike',
      name: 'spike',
      component: () => import('@/views/SpikeDemo.vue'),
    },
  ],
})

export default router
