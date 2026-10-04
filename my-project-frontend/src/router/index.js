import {createRouter, createWebHistory} from "vue-router";
import {unauthorized} from "@/net/index.js";

const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes: [
        {
            path: '/',
            name: 'welcome',
            component: () => import('@/views/WelcomeView.vue'),
            children: [
                {
                    path: '',
                    name: 'welcome-login',
                    component: () => import('@/views/welcome/LoginPage.vue'),
                }, {
                    path: 'register',
                    name: 'welcome-register',
                    component: () => import('@/views/welcome/RegisterPage.vue'),
                }, {
                    path: 'reset',
                    name: 'welcome-reset',
                    component: () => import('@/views/welcome/ResetPage.vue'),
                }
            ]
        }, {
            path: '/index',
            name: 'index',
            component: () => import('@/views/IndexView.vue'),
            children: [
                {
                    path: 'main-page',
                    name: 'main-page',
                    component: () => import('@/views/home/MainPage.vue'),
                }, {
                    path: 'tieba',
                    name: 'tieba',
                    component: () => import('@/views/forum/Tieba.vue'),
                }, {
                    // 帖子详情。挂在 IndexView 底下，所以顶部栏和右侧栏都不会卸载，
                    // 只有 <router-view> 里的内容被换掉。
                    // 有独立的 URL 才能分享、刷新、收藏，后退键也才有意义 ——
                    // 这也是为什么它不能做成一个"没有路由的弹窗"
                    path: 'topic/:id',
                    name: 'topic-detail',
                    component: () => import('@/views/forum/TopicDetail.vue'),
                }, {
                    // 别人的主页。也是挂在 IndexView 底下，顶部栏和右侧栏不卸载
                    path: 'user/:id',
                    name: 'user-profile',
                    component: () => import('@/views/forum/UserProfile.vue'),
                }, {
                    path: 'user-settings',
                    name: 'user-settings',
                    component: () => import('@/views/settings/UserSettings.vue'),
                }, {
                    // 隐私设置。侧栏「个人档案」下那一项
                    path: 'privacy',
                    name: 'privacy-settings',
                    component: () => import('@/views/settings/PrivacySettings.vue'),
                }
            ]
        }
    ]
})

router.beforeEach((to, from, next) => {
    const isUnauthorized = unauthorized();
    if(to.name?.startsWith('welcome-') && !isUnauthorized) {
        next('/index');
    } else if(to.fullPath.startsWith('/index') && isUnauthorized) {
        next('/');
    } else {
        next();
    }
})

export default router