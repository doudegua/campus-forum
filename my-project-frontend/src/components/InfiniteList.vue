<script setup>
import {computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch} from "vue";
import {Refresh} from "@element-plus/icons-vue";

/**
 * 游标分页 + 无限滚动。
 *
 * <h3>为什么把这个抽出来</h3>
 * 它是一段**微妙代码**：哨兵 + IntersectionObserver + keep-alive 时机的重连 +
 * 游标 + 防重复请求的守卫。我们为了其中一半调过一个小时。
 * 微妙代码不该有两份 —— 你在其中一份里修的 bug，不会自动跑到另一份去。
 *
 * <h3>它不知道自己在列什么</h3>
 * 请求哪个接口、每一行长什么样，都由外面给。这里只负责"翻页"这一件事。
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>{@code props.fetch(cursor)} —— 取一页。返回 {@code {items, nextCursor}}。
 *       出错就 throw，异常信息会显示在列表底部。{@code nextCursor} 为 null 表示到底了</li>
 *   <li>{@code props.query} —— <b>只用来判断"该不该重拉"</b>：它的内容一变就从头重拉。
 *       真正带什么参数由 fetch 自己决定</li>
 *   <li>{@code slot #item="{item}"} —— 每一项怎么渲染</li>
 *   <li>{@code slot #empty} —— 到底了但一条都没有时显示什么</li>
 *   <li>{@code expose refresh()} —— 手动重拉，给"命令式"的场景用</li>
 * </ul>
 */

const props = defineProps({
  /** (cursor) => Promise<{items: any[], nextCursor: any}> */
  fetch: {type: Function, required: true},
  /**
   * 重拉信号。只比内容，不比引用。
   * <p>
   * 为什么不能直接 watch(props.query)：外面写成 :query="{uid: 1}" 的话，
   * 父组件**每次重渲染都会新建一个对象**，引用变了就会触发 ——
   * 结果是父组件随便更新点别的，列表都重拉一遍。加 deep 也救不了，引用变化本身就触发。
   */
  query: {type: Object, default: () => ({})},
  /**
   * 怎么算出每一项的身份，用来给 v-for 当 key。
   * 不传就退回用下标 —— 这里永远是"往后追加"，下标也是稳的。
   * 但哪天列表开始支持重排序，下标就不成立了，那时候记得传这个。
   */
  itemKey: {type: Function, default: null},
})

const items = ref([])
/** 上一页给的边界。null 表示没有下一页（或者还没开始加载） */
const nextCursor = ref(null)
const loading = ref(false)
/** 后端说没有更多了 */
const finished = ref(false)
const error = ref('')

/** 列表底部的哨兵，它滚进视野就加载下一页 */
const sentinel = ref(null)
let observer = null

async function loadMore() {
  // 这两句是必需的：滚动会连续触发好多次，没有它们就会打出好几个一样的请求
  if (loading.value || finished.value) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    const page = await props.fetch(nextCursor.value)
    items.value.push(...(page?.items ?? []))
    // 用 ?? 而不是 ||：万一下一页的游标真的是 0，|| 会把它当成"到底了"
    nextCursor.value = page?.nextCursor ?? null
    if (nextCursor.value == null) {
      finished.value = true
    }
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function reset() {
  items.value = []
  nextCursor.value = null
  finished.value = false
  error.value = ''
  loadMore()
}

watch(computed(() => JSON.stringify(props.query)), reset)

function connectObserver() {
  disconnectObserver()
  if (!sentinel.value) return
  observer = new IntersectionObserver(entries => {
    if (entries[0].isIntersecting) {
      loadMore()
    }
  }, {rootMargin: '200px'})   // 提前 200px 触发，别等真的滚到底才开始加载
  observer.observe(sentinel.value)
}

function disconnectObserver() {
  observer?.disconnect()
  observer = null
}

onMounted(() => {
  // 只在第一次挂载时拉一页。被 keep-alive 缓存之后再回来不走这里 —— 那正是我们要的
  loadMore()
  connectObserver()
})

// 被 keep-alive 缓存后再回来时，哨兵虽然还是同一个节点，但中途离开过文档，
// 原来那个 observer 已经不会正常触发了，所以这里重连一次。
//
// 如果这个组件不在 keep-alive 里（比如详情页的评论列表），重连也只是把刚建好的
// 观察器换掉，没有副作用 —— 所以不用去纠结 onActivated 在不在挂载时触发，
// 两种语义下这段都是对的。
onActivated(connectObserver)
onDeactivated(disconnectObserver)
onBeforeUnmount(disconnectObserver)

/**
 * 手动重拉。只有"命令"才该走这条路 —— 条件变化走 query prop。
 * 判据：能靠数据变化表达的，就别做成命令，因为"记得喊一声"是不可靠的。
 */
defineExpose({refresh: reset})
</script>

<template>
  <div class="infinite-list">
    <div class="list-toolbar">
      <el-button link :loading="loading" @click="reset">
        <el-icon style="margin-right: 4px; vertical-align: -2px"><Refresh/></el-icon>
        刷新
      </el-button>
    </div>

    <div class="list">
      <template v-for="(item, index) in items" :key="itemKey ? itemKey(item) : index">
        <!-- 把每一项原样交回给调用方渲染。这里不认识 item 是什么 -->
        <slot name="item" :item="item" :index="index"/>
      </template>

      <!-- 哨兵：滚到这里就加载下一页。必须一直在，别塞进 v-if 里 ——
           它要是被条件渲染掉了，IntersectionObserver 就再也观察不到了 -->
      <div ref="sentinel" style="height: 1px"></div>

      <div class="list-status" v-if="loading">加载中…</div>
      <div class="list-status" v-else-if="error">
        <span style="color: var(--el-color-danger)">{{ error }}</span>
        <el-button link type="primary" @click="loadMore">重试</el-button>
      </div>
      <div class="list-status" v-else-if="finished && items.length">没有更多了</div>
      <!-- 空状态每个页面不一样，所以开个插槽；不传就用默认的 -->
      <div class="list-status" v-else-if="finished">
        <slot name="empty">暂无内容</slot>
      </div>
    </div>
  </div>
</template>

<style scoped>
.infinite-list {
  display: flex;
  flex-direction: column;
}

.list-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
  font-size: 14px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* 列表底部那几行状态文字（加载中 / 出错 / 没有更多 / 空） */
.list-status {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
