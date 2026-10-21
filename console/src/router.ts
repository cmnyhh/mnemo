import { createRouter, createWebHashHistory } from 'vue-router'

/**
 * hash 路由：静态资源由 Spring Boot 直接吐，不需要为 history 模式配 404 回退规则。
 * 少一条服务端规则，就少一个「本地好、线上 404」的来源。
 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/overview' },
    {
      path: '/overview',
      name: 'overview',
      meta: { title: '概览' },
      component: () => import('./views/OverviewView.vue'),
    },
    {
      path: '/records',
      name: 'records',
      meta: { title: '记忆库' },
      component: () => import('./views/RecordsView.vue'),
    },
    {
      path: '/recall',
      name: 'recall',
      meta: { title: '召回测试' },
      component: () => import('./views/RecallView.vue'),
    },
    {
      path: '/learning',
      name: 'learning',
      meta: { title: '学习' },
      component: () => import('./views/LearningView.vue'),
    },
    {
      path: '/config',
      name: 'config',
      meta: { title: '配置' },
      component: () => import('./views/ConfigView.vue'),
    },
    {
      path: '/tokens',
      name: 'tokens',
      meta: { title: '令牌' },
      component: () => import('./views/TokensView.vue'),
    },
    {
      path: '/system',
      name: 'system',
      meta: { title: '系统' },
      component: () => import('./views/SystemView.vue'),
    },
    {
      path: '/observability',
      name: 'observability',
      meta: { title: '观测' },
      component: () => import('./views/ObservabilityView.vue'),
    },
    { path: '/:pathMatch(.*)*', redirect: '/overview' },
  ],
})
