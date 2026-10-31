<script setup>
import {computed, ref} from "vue";
import axios from "axios";
import {useRouter} from "vue-router";
import {accessHeader} from "@/net";
import InfiniteList from "@/components/InfiniteList.vue";
import ConfirmCard from "@/components/ConfirmCard.vue";
import {formatFullTime} from "@/utils/time.js";
import {Delete} from "@element-plus/icons-vue";
import {ElMessage} from "element-plus";
import {useStore} from "@/store";

/**
 * 一条帖子下面的评论列表。
 * <p>
 * 翻页机制全在 {@link InfiniteList} 里 —— 和 TopicList 共用同一份。
 * 这就是把它抽出来的收益：评论这一块只写了"请求哪个接口"和"每条长什么样"。
 */

const props = defineProps({
  topicId: {type: Number, required: true},
})

/**
 * 删掉一条评论之后喊一声。
 * <p>
 * 为什么不让父组件监听列表数据自己算：那是把 InfiniteList 内部的分页状态
 * 透出去，父组件就得知道"当前加载了几页"。这里只需要一个**事件** ——
 * "少了一条"，父组件拿它把帖子上的计数减一就够了。
 */
const emit = defineEmits(['deleted'])

const router = useRouter()
const store = useStore()
const listRef = ref(null)

/** 正在删的那条评论 id。用它只让被点的那颗按钮转圈，而不是整列一起转 */
const deleting = ref(null)

/**
 * 待删除的那条评论 + 确认卡片开关。
 * <p>
 * 卡片是"先问再做"，所以必须把"要删哪一条"记在组件里 ——
 * 卡片自己只知道标题文案，它不认识评论。
 */
const pendingComment = ref(null)
const confirmVisible = ref(false)

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

/**
 * 这条评论是不是"我"发的。
 * <p>
 * 和详情页那两个按钮一样，这只是**藏按钮**，不是权限 ——
 * 真正的判断在后端 deleteComment 里的 {@code exist.getUid().equals(uid)}。
 */
function canDelete(comment) {
  return comment.authorId != null && comment.authorId === store.user?.id
}

/** 点删除按钮：只记住要删哪一条并把卡片叫出来，一行数据都不动 */
function askRemove(comment) {
  pendingComment.value = comment
  confirmVisible.value = true
}

/** 卡片里真的确认了，才发请求 */
async function doRemove() {
  const comment = pendingComment.value
  // 防御：理论上走不到（卡片只能从 askRemove 打开），但拿不到待删对象就没法删，
  // 静默返回比带着 null.id 去发请求好
  if (!comment || deleting.value != null) {
    return
  }
  deleting.value = comment.id
  try {
    const {data} = await axios.delete(`/api/forum/comment/${comment.id}`, {headers: accessHeader()})
    if (data.code !== 200) {
      throw new Error(data.message)
    }
    ElMessage.success('已删除')
    // 先通知父组件把帖子上的计数减一，再刷新列表。
    // 顺序其实无所谓（两件事互不依赖），但先说"少了一条"更贴合因果
    emit('deleted')
    // 整列重拉，而不是"从本地数组里抠掉这一条"：
    // 评论列表的分页状态在 InfiniteList 内部，外面拿不到 items。
    // 为了抠一条而把它 expose 出去，是拿封装换一次网络请求，不划算
    listRef.value?.refresh()
  } catch (e) {
    ElMessage.error('删除失败：' + (e.message || e))
  } finally {
    deleting.value = null
    pendingComment.value = null
  }
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
          <!--
            margin-left: auto 把它推到最右边 —— 不用靠"时间那一栏拉长"之类的技巧。
            只有作者本人看得到（见 canDelete）
          -->
          <el-button v-if="canDelete(item)"
                     class="comment-delete"
                     link
                     type="danger"
                     aria-label="删除评论"
                     :loading="deleting === item.id"
                     @click="askRemove(item)">
            <el-icon><Delete/></el-icon>
          </el-button>
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

  <!--
    删除确认。放在 infinite-list 外面 —— 它 teleport 到 body，
    位置和列表无关；而列表是 v-for 出来的，塞进去会变成每行一个实例
  -->
  <confirm-card v-model="confirmVisible"
                title="确定删除这条评论？"
                message="删除后不可恢复。"
                confirm-text="删除"
                @confirm="doRemove"/>
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

.comment-delete {
  /* 推到行尾。不给它留固定宽度 —— 按钮是 link 样式，宽度本来就很窄 */
  margin-left: auto;
  /* link 按钮默认带一点横向 padding，在这么窄的一行里会把作者名和时间挤开 */
  padding: 0 4px;
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
