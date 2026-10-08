import {defineStore} from "pinia";

/**
 * 论坛列表的"过期"信号。
 *
 * <h3>它解决的是什么问题</h3>
 * IndexView 里 {@code <keep-alive :include="['Tieba']">} 把论坛页缓存住了 ——
 * 从详情页返回时 Tieba 组件**不会重建**，onMounted 不跑，列表里已经加载的
 * 20/40/60 条和 nextCursor 全都还在。这是好事：回来时不用从头重拉，
 * 滚动位置也保得住。
 *
 * 但它有个代价：**详情页里改掉/删掉的那条帖子，列表不知道。**
 * 删了一条帖子再返回，它会原样留在列表里；改了标题，列表还是旧标题。
 *
 * <h3>为什么不能直接在 onActivated 里无条件 refresh</h3>
 * 那就是把 keep-alive 的收益全扔了 —— 每次从任意详情页返回都清空重拉，
 * 用户滚了半天的位置和已加载的几十条一起没了。绝大多数返回是"看完就走"，
 * 数据根本没变。
 *
 * <h3>为什么不做成"返回就刷新"</h3>
 * 因为真正需要刷新的只有一种情况：**这次离开详情页之前发生了写操作**。
 * 那就把这件事明确记下来，而不是让列表页去猜。
 *
 * <h3>用法</h3>
 * 写操作成功后 {@code markTopicListStale()}；列表页在 onActivated 里
 * {@code consumeTopicListStale()}，返回 true 才重拉。
 *
 * "consume"（读一次就清掉）而不是"读"：这个信号是**一次性**的。
 * 留在那儿的话，用户这次是去用户主页、下次才回论坛页，也会莫名其妙重拉一遍。
 */
export const useForumStore = defineStore('forum', {
    state: () => ({
        /**
         * 被缓存的帖子列表已经过期。
         * <p>
         * 只有"列表页自己会缓存"的事实让这个字段有意义 —— 如果哪天 Tieba
         * 从 keep-alive 的 include 里拿掉了，这个 store 就该一起删掉
         * （那时候 onMounted 每次都会跑，列表永远是新拉的）。
         */
        staleTopicList: false,
    }),
    actions: {
        /** 帖子被编辑或删除后调用。改标题也要调 —— 列表显示的就是标题 */
        markTopicListStale() {
            this.staleTopicList = true
        },
        /**
         * 取走信号并清空。返回"取走之前它是不是 true"。
         * <p>
         * 不用"先读再置 false"两步：那样在两处调用之间漏掉一次赋值，
         * 就会变成"每次返回都重拉"或者"永远不重拉"，而且都不报错。
         */
        consumeTopicListStale() {
            const stale = this.staleTopicList
            this.staleTopicList = false
            return stale
        },
    },
})
