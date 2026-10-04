<script setup>
import {computed, ref} from "vue";
import axios from "axios";
import {accessHeader} from "@/net";
import TopicCard from "@/views/forum/components/TopicCard.vue";
import InfiniteList from "@/components/InfiniteList.vue";

/**
 * 帖子列表。
 * <p>
 * 翻页、三态、无限滚动那些机制都在 {@link InfiniteList} 里了。
 * 这里只剩两件"具体"的事：请求哪个接口、每一项长什么样。
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>{@code props.query} —— 查询条件 {@code {uid?, types?}}。<b>内容一变就重拉</b></li>
 *   <li>{@code props.size} —— 一次取多少条</li>
 *   <li>{@code slot #empty} —— 列表为空时显示什么，不传用默认文案</li>
 *   <li>{@code expose refresh()} —— 手动重拉，给"发帖成功后"这种命令式场景用</li>
 * </ul>
 */
const props = defineProps({
  /** {uid?: number, types?: number[]} */
  query: {type: Object, default: () => ({})},
  /** 一次加载多少条。后端 @Max(50) 会拦，这里自己定 */
  size: {type: Number, default: 20},
})

const listRef = ref(null)

/** 只干一件事：把游标换成这一页的数据。翻页的调度全在 InfiniteList 那边 */
async function fetchPage(cursor) {
  // 手拼 query string，不用 axios 的 params 对象。
  // 原因：数组必须序列化成**重复的键**（types=1&types=2），
  // 而 axios 默认给的是 types[]=1 —— 带方括号的键名 Spring 的 @ModelAttribute
  // 认不出来，types 会静默变成 null。表现是"筛选点了、请求也发了、结果没变"。
  const params = new URLSearchParams()
  params.set('size', String(props.size))
  if (props.query?.uid != null) {
    params.set('uid', String(props.query.uid))
  }
  for (const type of props.query?.types ?? []) {
    params.append('types', String(type))
  }
  if (cursor != null) {
    params.set('cursor', String(cursor))
  }

  const {data} = await axios.get(`/api/forum/list-topic?${params}`, {headers: accessHeader()})
  if (data.code !== 200) {
    throw new Error(data.message)
  }
  const page = data.data ?? {}
  return {items: page.topicList ?? [], nextCursor: page.nextCursor}
}

/**
 * 归一化之后再交给 InfiniteList 当"重拉信号"。
 * types 排个序，[1,2] 和 [2,1] 才会被当成同一个条件。
 */
const normalizedQuery = computed(() => ({
  uid: props.query?.uid ?? null,
  types: [...(props.query?.types ?? [])].sort((a, b) => a - b),
}))

defineExpose({refresh: () => listRef.value?.refresh()})
</script>

<template>
  <infinite-list ref="listRef"
                 :fetch="fetchPage"
                 :query="normalizedQuery"
                 :item-key="item => item.id">
    <template #item="{item}">
      <topic-card :topic="item"/>
    </template>
    <template #empty>
      <slot name="empty">还没有帖子</slot>
    </template>
  </infinite-list>
</template>
