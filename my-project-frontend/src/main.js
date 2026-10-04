import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { createPinia } from 'pinia'
import 'qweather-icons/font/qweather-icons.css'

import 'element-plus/theme-chalk/dark/css-vars.css'

/*
 * 这里**故意不设** axios.defaults.baseURL，也不 import axios。
 *
 * 原来写的是：
 *     axios.defaults.baseURL = 'http://localhost:8080'
 * 那行导致部署到服务器后整个前端不可用 —— 浏览器会去请求
 *     http://localhost:8080/api/...
 * 也就是**访问者自己电脑的 8080 端口**，而不是服务器。后端在服务器上，
 * 访问者本机没有，于是 axios 直接抛 Network Error
 * （连不上，拿不到任何 HTTP 响应 —— 和"服务器返回 401/500"是两回事）。
 *
 * 不设 baseURL 时 axios 用相对路径，请求跟着当前页面的域名走，于是：
 *     本地开发  请求 localhost:5173/api/...  → Vite 的 proxy 转给 8080
 *     线上部署  请求 8.155.132.128/api/...   → nginx 反代给 backend 容器
 *
 * **同一份构建产物，两种环境都对**，不需要"打包时按环境改地址"。
 * 这也是为什么换域名不用重新构建。
 *
 * 代价要说清：这依赖"开发时的 proxy"和"线上的反代"这两套配置都存在。
 * 少了任何一个，症状都是首页能打开、但所有接口不通。
 *
 * 注：别的文件各自 `import axios from 'axios'`，拿到的都是同一个模块实例，
 * 所以这里不需要 import 它 —— 保持这个文件只管"装配应用"。
 */

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
