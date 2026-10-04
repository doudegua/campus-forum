<script setup>
import {get, logout} from "@/net/index.js";
import router from "@/router/index.js";
import {useStore} from "@/store";
import {computed, nextTick, onBeforeUnmount, onMounted, reactive, ref} from "vue";
import {useRoute} from "vue-router";
import {
  CameraFilled,
  ChatDotSquare,
  Collection,
  FullScreen,
  Headset,
  Location,
  Memo,
  Moon,
  Mute,
  Search,
  Sunny,
  Ticket,
  Unlock,
  Bowl
} from "@element-plus/icons-vue";
import {defaultAvatar} from "@/constants/index.js";
import {useDark} from "@vueuse/core";

const store = useStore();
const isDark = useDark()
// const loading = ref(true)
const searchInput = reactive({
  type: '',
  text: ''
})

/**
 * 搜索范围下拉框的选项 —— 版块列表，从后端拿。
 * <p>
 * 原来这里是 5 个写死的 el-option，两个问题：
 *   1. 文案是随手编的，有几项内容很不合适，不能出现在这个项目里
 *   2. 就算把文案换掉，value 也和 db_topic_type.id 对不上 ——
 *      数据库里 3 是"闲聊灌水"，写死的那份 3 却是另一个意思，
 *      选了它发给后端的 type 就是错的
 * 所以改成和 Tieba.vue / TopicEditor.vue 一样走接口，分类只在一处维护。
 * <p>
 * 拉不到就只剩"全部"一个选项：搜索框的其它部分还能用，不该因为
 * 版块列表拿不到就整页出问题。
 */
const topicTypes = ref([])

get('/api/forum/topic_type', (data) => {
  topicTypes.value = data ?? []
})

/* ---------------- 左侧导航高亮 ---------------- */

const route = useRoute()

/**
 * 左边导航该亮哪一项。
 * <p>
 * <b>不能直接用 $route.path。</b>那只在"每个页面自己就是一个菜单项"的时候成立。
 * 现在能点的菜单项只有 tieba / user-settings / privacy（限登录后）+ 我的帖子，
 * 而 /index/topic/85（帖子详情）和 /index/user/4（用户主页）都不是其中任何一个 ——
 * 于是整个左侧导航一个都不亮，用户看到的回答是"我不知道你在哪"。
 * <p>
 * 所以要把路径映射到它**属于哪个板块**：这两种页面都是论坛里的页面，
 * 所以高亮"千度贴吧"。加了新的详情类页面，就在这儿加一条映射。
 * <p>
 * 这类 bug 的共同点是：不报错、不影响功能，只是"哪里不太对"，
 * 所以能放很久没人管 —— 详情页那个就已经躺了好几天了。
 */
const activeMenu = computed(() => {
  const path = route.path
  // 自己的主页在菜单里有对应项（"我的帖子"），直接高亮它，别落到"千度贴吧"去
  if (store.user?.id != null && path === `/index/user/${store.user.id}`) {
    return path
  }
  // 其余详情类页面都属于论坛板块
  if (path.startsWith('/index/topic/') || path.startsWith('/index/user/')) {
    return '/index/tieba'
  }
  // 其余页面自己就是菜单项，原样返回
  return path
})

/* ---------------- 页面滚动位置记忆 ---------------- */

/**
 * 滚动容器是 <el-main> 里那个 el-scrollbar，不是 window ——
 * 所以浏览器自带的历史滚动恢复（history.scrollRestoration）管不到它，
 * 只能自己记。key 用 fullPath，这样每个页面各记一份。
 */
const scrollbar = ref(null)
const scrollMemory = new Map()

let removeBeforeGuard = null
let removeAfterGuard = null

onMounted(() => {
  removeBeforeGuard = router.beforeEach((to, from) => {
    // 必须在导航**之前**读：afterEach 时 DOM 已经换成新页面，
    // 那时候容器的内容高度可能更矮，scrollTop 已经被夹掉了
    if (from.fullPath) {
      scrollMemory.set(from.fullPath, scrollbar.value?.wrapRef?.scrollTop ?? 0)
    }
  })

  removeAfterGuard = router.afterEach((to) => {
    const top = scrollMemory.get(to.fullPath) ?? 0
    // 等一个 tick 再滚：afterEach 触发时 <router-view> 还没换完，
    // 容器的 scrollHeight 还是上一页的，这时候设 scrollTop 会被静默夹到 0
    nextTick(() => scrollbar.value?.setScrollTop(top))
  })
})

onBeforeUnmount(() => {
  // 守卫是全局的，不摘掉的话 IndexView 每次重建都会再挂一个，
  // 最后同一个滚动位置被记录 N 遍
  removeBeforeGuard?.()
  removeAfterGuard?.()
})

get('/api/user/info', (data) => {
  store.user = data;
  // loading.value = false;
})

get('/api/user/profile', (data) => {
  store.profile = data
  // 头像和帖子配图现在是同一条路：avatar 里存的就是图片 key，
  // 拼成 URL 直接交给 <img src> 就行 —— 不用再 fetch 成 blob 再转 objectURL。
  // 以前那样做的话，浏览器既没法缓存这张图，也没法并行加载它
  if (data.avatar) {
    store.profile.avatarUrl = `/api/image/${data.avatar}`
  }
})

function userLogout() {
  logout(() => router.push("/"));
}
</script>

<!--<template>-->
<!--  <div>-->
<!--    <el-button @click="userLogout">退出登录</el-button>-->
<!--  </div>-->
<!--</template>-->
<template>
  <div class="main-content" v-loading="loading" element-loading-text="Loading, please wait...">
    <el-container style="height: 100%" v-if="!loading">
      <el-header class="main-content-header">
        <div style="flex: 1">
          <el-image class="logo" src="https://element-plus.org/images/element-plus-logo.svg"></el-image>
        </div>
        <div>
          <el-button @click="userLogout">退出登录</el-button>
        </div>
        <div style="flex: 1;padding: 0 20px;text-align: center">
          <el-input v-model="searchInput.text" class="search-input" style="width: 100%;max-width: 500px" placeholder="搜索...">
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
            <template #append>
              <!--
                搜索范围。选项从后端拿（GET /api/forum/topic_type），不写死 ——
                写死的话，数据库里改了版块名这里不会跟着变；更要紧的是
                value 会和 db_topic_type.id 脱钩，选出来的分类是错的。

                ⚠️ 但要说清现状：这个搜索框本身**还没接线**（searchInput 从没
                发给任何接口）。所以现在这个下拉只是把选项显示对了，
                点搜索仍然不会有反应 —— 要真能搜，得在后端加搜索端点。
              -->
              <el-select v-model="searchInput.type"
                         style="width: 100px"
                         :show-arrow="false"
                         popper-class="light-card-popper"
                         placeholder="搜索分类">
                <el-option value="" label="全部"/>
                <el-option v-for="t in topicTypes" :key="t.id"
                           :value="t.id" :label="t.name"/>
              </el-select>
            </template>
          </el-input>
        </div>
        <div style="flex: 1" class="user-info">
          <div class="theme-switch" title="切换深色/浅色模式">
            <el-icon :class="{active: !isDark}"><Sunny/></el-icon>
            <el-switch v-model="isDark" aria-label="切换深色或浅色模式"/>
            <el-icon :class="{active: isDark}"><Moon/></el-icon>
          </div>
          <el-divider class="user-divider" direction="vertical"/>
          <div class="profile">
            <div>{{store.user.username}}</div>
            <div>{{store.user.email}}</div>
          </div>
          <el-dropdown :show-arrow="false" popper-class="light-card-popper">
            <el-avatar :src="store.profile.avatarUrl || defaultAvatar"/>
            <template #dropdown>
              <!--
                「账号设置」和侧栏「个人档案 → 个人信息」指向同一个路由。
                两处入口标签不同是有意的：侧栏那是"档案下的一个页面"，
                这里是"点头像想改点什么"的快捷入口，用词跟着场景走更顺。
              -->
              <el-dropdown-item @click="router.push('/index/user-settings')">
                <el-icon><Ticket /></el-icon>
                账号设置
              </el-dropdown-item>
              <el-dropdown-item>
                <el-icon><Headset /></el-icon>
                消息通知
              </el-dropdown-item>
              <el-dropdown-item @click="userLogout" divided>
                <el-icon><Unlock /></el-icon>
                退出登录
              </el-dropdown-item>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-container>
        <el-aside width="230px">
          <el-scrollbar style="height: calc(100vh - 55px)">
            <el-menu
                router
                :default-active="activeMenu"
                :default-openeds="['1','2','3']"
                style="min-height: calc(100vh - 55px)">
              <el-sub-menu index="1">
                <template #title>
                  <el-icon><location/></el-icon>
                  <span><b>校园论坛</b></span>
                </template>
                <el-menu-item index="/index/tieba">
                  <template #title>
                    <el-icon><chat-dot-square/></el-icon>
                    千度贴吧
                  </template>
                </el-menu-item>
                <!--
                  下面是**占位项**：没有 index 属性，所以点了不导航（el-menu 的
                  router 模式只在有 index 时才 push）。留着是为了先把版块位置占住，
                  等真有页面了再补 index 和对应路由 —— 补的时候只需动这一处。
                -->
                <el-menu-item>
                  <template #title>
                    <el-icon><bowl/></el-icon>
                    笑川粥饮
                  </template>
                </el-menu-item>
              </el-sub-menu>
              <el-sub-menu index="2">
                <template #title>
                  <el-icon><FullScreen /></el-icon>
                  <span><b>探索·发现</b></span>
                </template>
                <!--
                  空子菜单会被 Element Plus 渲染成一个点开什么都没有的面板，
                  看起来像没做完。所以放一个禁用态的占位项说明"这里以后会有东西"，
                  比留个空壳强。disabled 让它点不动，也不会误导成可用的功能。
                -->
                <el-menu-item disabled>
                  <template #title>
                    <el-icon><Memo /></el-icon>
                    敬请期待
                  </template>
                </el-menu-item>
              </el-sub-menu>
              <el-sub-menu index="3">
                <template #title>
                  <el-icon><CameraFilled /></el-icon>
                  <span><b>个人档案</b></span>
                </template>
                <el-menu-item index="/index/user-settings">
                  <template #title>
                    <el-icon><Ticket /></el-icon>
                    个人信息
                  </template>
                </el-menu-item>
                <!--
                  "我的帖子"不是一个新页面 —— 它就是"看自己的用户主页"。
                  后端在 targetId == viewerId 时不过滤隐私开关，所以这一页
                  看到的资料和帖子都是完整的。
                  地址里的 id 来自 store.user.id（/api/user/info 现在会返回它）。
                -->
                <el-menu-item v-if="store.user?.id != null" :index="`/index/user/${store.user.id}`">
                  <template #title>
                    <el-icon><Collection /></el-icon>
                    我的帖子
                  </template>
                </el-menu-item>
                <el-menu-item index="/index/privacy">
                  <template #title>
                    <el-icon><Mute /></el-icon>
                    隐私设置
                  </template>
                </el-menu-item>
              </el-sub-menu>
            </el-menu>
          </el-scrollbar>
        </el-aside>
        <el-main class="main-content-page">
          <!--
            keep-alive 只缓存 Tieba（靠 defineOptions 里的组件名匹配）：
            进详情再回来时，列表组件不会被销毁重建，已经加载的 20/40/60 条
            和 nextCursor 都还在，不会从头重拉。

            但光有 keep-alive 不够 —— DOM 被摘出文档再插回来时，
            容器高度中途缩过一次，scrollTop 已经被夹掉了，所以还要靠上面
            那对 router 守卫把位置存下来再滚回去。两件事是一套的。
          -->
          <el-scrollbar ref="scrollbar" style="height: calc(100vh - 55px)">
            <router-view v-slot="{ Component }">
              <keep-alive :include="['Tieba']">
                <component :is="Component"/>
              </keep-alive>
            </router-view>
          </el-scrollbar>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>


<style lang="less" scoped>
.main-content-page{
  padding: 0;
  background: #f7f8fa;
}

:global(html.dark .main-content-page){
  background: #242628;
}

.main-content{
  height: 100vh;
  width: 100vw;
}

.search-input {
  --el-input-border-radius: 10px;
  --el-border-radius-base: 10px;
}

.search-input :deep(.el-input__wrapper) {
  border-radius: 10px;
}

.search-input :deep(.el-input-group__append) {
  margin-left: 10px;
  border-radius: 17px;
  background-color: var(--el-bg-color);
  box-shadow: none;
}

.search-input :deep(.el-input-group__append .el-select__wrapper) {
  border-radius: 17px;
  background-color: var(--el-bg-color);
  box-shadow: none;
}

:deep(.el-menu-item),
:deep(.el-sub-menu__title) {
  position: relative;
  isolation: isolate;
}

:deep(.el-menu-item:hover),
:deep(.el-menu-item.is-active),
:deep(.el-sub-menu__title:hover) {
  background-color: transparent;
}

:deep(.el-menu-item:hover::before),
:deep(.el-menu-item.is-active::before),
:deep(.el-sub-menu__title:hover::before) {
  content: "";
  position: absolute;
  inset: 5px 10px;
  z-index: -1;
  //border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background-color: var(--el-menu-hover-bg-color);
}

.main-content-header{
  border-bottom: solid 1px var(--el-border-color);
  height: 55px;
  display: flex;
  align-items: center;
  box-sizing: border-box;
  .logo{
    height: 32px;
  }
  .user-info{
    height: 100%;
    display: flex;
    justify-content: flex-end;
    align-items: center;

    .theme-switch {
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--el-text-color-placeholder);

      .el-icon {
        transition: color var(--el-transition-duration-fast);

        &.active {
          color: var(--el-color-primary);
        }
      }
    }

    .user-divider {
      height: calc(100% - 16px);
      margin: 8px 16px;
    }

    .el-avatar:hover{
      cursor: pointer;
    }

    .profile{
      text-align: right;
      margin-right: 20px;
      :first-child{
        font-size: 18px;
        font-weight: bold;
      }
      :last-child{
        font-size: 10px;
        color: gray;
      }
    }
  }
}
</style>
