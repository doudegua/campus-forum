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
            /*
             * `/index` 本身没有页面内容 —— 它的 children 才是真正的页面，
             * 内容渲染在 IndexView 里的 <router-view> 中。
             * 所以不写 redirect 的话，登录后跳到 /index 会看到
             * **顶部栏、侧栏都在，中间一片空白**（<router-view> 没东西可渲染）。
             *
             * 重定向到 tieba：论坛是主功能，进来就该看到帖子列表。
             * main-page（那个只有时间和欢迎语的页面）菜单项之前已经删掉了，
             * 所以没有任何入口指向它，更不该把它当默认页。
             *
             * 用 redirect 而不是加一个 `path: ''` 的空组件：
             * redirect 会让地址栏变成 /index/tieba，用户能直接看到真实地址、
             * 也能收藏和分享；空组件方案地址栏停在 /index，
             * 刷新或分享时又得靠一次额外的判断才不空白。
             */
            redirect: '/index/tieba',
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