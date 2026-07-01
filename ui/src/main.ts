import './assets/css/main.css'

import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import { createPinia } from 'pinia'
import VueApexCharts from 'vue3-apexcharts'
import ui from '@nuxt/ui/vue-plugin'
import App from './App.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', name: 'chat', component: () => import('@/pages/chat/index.vue') },
    { path: '/dashboard', name: 'dashboard', component: () => import('@/pages/dashboard/index.vue') },
    { path: '/ingestion', name: 'ingestion', component: () => import('@/pages/ingestion/index.vue') },
    { path: '/evaluation', name: 'evaluation', component: () => import('@/pages/evaluation/index.vue') },
    { path: '/settings', name: 'settings', component: () => import('@/pages/settings/index.vue') },
  ],
})

const pinia = createPinia()
const app = createApp(App)

app.use(pinia)
app.use(router)
app.use(VueApexCharts)
app.use(ui)

app.mount('#app')
