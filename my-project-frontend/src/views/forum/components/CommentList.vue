<script setup>
import {computed, ref} from "vue";
import axios from "axios";
import {useRouter} from "vue-router";
import {accessHeader} from "@/net";
import InfiniteList from "@/components/InfiniteList.vue";
import {formatFullTime} from "@/utils/time.js";

/**
 * 一条帖子下面的评论列表。
 * <p>
 * 翻页机制全在 {@link InfiniteList} 里 —— 和 TopicList 共用同一份。
 * 这就是把它抽出来的收益：评论这一块只写了"请求哪个接口"和"每条长什么样"。
 */

const props = defineProps({
  topicId: {type: Number, required: true},
})

const router = useRouter()
const listRef = ref(null)

async function fetchPage(cursor) {
  const params = new URLSearchParams()
  params.set('topicId', String(props.topicId))
  params.set('size', '20')
  if (cursor != null) {
    params.set('cursor', String(cursor))
  }

  const {data} = await axios.get(`/api/forum/comment?${params}`, {headers: accessHeader()})
  if (data.code !== 200) {
    throw new Error(data.message)
  }
  const page = data.data ?? {}
  return {items: page.commentList ?? [], nextCursor: page.nextCursor}
}

/** 帖子换了（在详情页之间跳）就要重拉 */
const query = computed(() => ({topicId: props.topicId}))

function openAuthor(comment) {
  router.push(`/index/user/${comment.authorId}`)
}

defineExpose({refresh: () => listRef.value?.refresh()})
</script>

<template>
  <infinite-list ref="listRef"
                 :fetch="fetchPage"
                 :query="query"
                 :item-key="comment => comment.id">
    <template #item="{item}">
      <div class="comment">
        <div class="comment-head">
          <span class="author" @click="openAuthor(item)">{{ item.authorName }}</span>
          <span class="time">{{ formatFullTime(item.time) }}</span>
        </div>
        <!--
          正文用 v-html。这不是"信任用户输入"—— 后端在入库前把整段文本
          转义过了（&lt; 存进去就是 &amp;lt;），所以这里拿到的永远是安全 HTML。
          换行也在后端转成了 <br>，所以不用靠 CSS 的 white-space 去还原。
        -->
        <div class="comment-body" v-html="item.content"></div>
      </div>
    </template>
    <template #empty>还没有评论，来说两句</template>
  </infinite-list>
</template>

<style scoped>
.comment {
  padding: 10px 12px;
  border-radius: 10px;
  background-color: var(--el-fill-color-light);
}

.comment-head {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.author {
  color: var(--el-text-color-primary);
  font-weight: 500;

  &:hover {
    cursor: pointer;
    color: var(--el-color-primary);
  }
}

.comment-body {
  margin-top: 6px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--el-text-color-primary);
  /* 长 URL 或连续字符不会把卡片撑破 */
  word-break: break-word;
}

/* 转义后的正文理论上只有文本和 <br>，但留着这两条以防以后评论支持富文本 */
.comment-body :deep(p) {
  margin: 0 0 6px;
}

.comment-body :deep(p:last-child) {
  margin-bottom: 0;
}
</style>
