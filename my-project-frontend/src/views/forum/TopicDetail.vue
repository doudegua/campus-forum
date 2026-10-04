<script setup>
import {ref, watch} from "vue";
import {useRoute, useRouter} from "vue-router";
import axios from "axios";
import {accessHeader} from "@/net";
import CardLight from "@/components/CardLight.vue";
import ForumSidebar from "@/views/forum/components/ForumSidebar.vue";
import CommentList from "@/views/forum/components/CommentList.vue";
import {ArrowLeft, Star, StarFilled} from "@element-plus/icons-vue";
import {ElMessage} from "element-plus";
import {formatFullTime} from "@/utils/time.js";
import {useGoBack} from "@/utils/navigation.js";

const route = useRoute()
const router = useRouter()
const goBack = useGoBack()

const topic = ref(null)
const loading = ref(true)
/** 空串 = 没出错。非空就是给用户看的原因（含"帖子不存在"） */
const error = ref('')

/* ---------------- 评论 ---------------- */

const commentListRef = ref(null)
const commentText = ref('')
const commentSending = ref(false)

/* ---------------- 点赞 ---------------- */

const likeSending = ref(false)

async function toggleLike() {
  // loading 期间按钮本身就是禁用的，这句是第二道保险
  if (likeSending.value) return
  likeSending.value = true
  // 要表达的是"我要它变成什么样"，不是"翻一下"：
  // 当前没赞 → POST（赞），当前已赞 → DELETE（取消）
  const method = topic.value.liked ? 'delete' : 'post'
  try {
    const {data} = await axios.request({
      url: `/api/forum/topic/${topic.value.id}/like`,
      method,
      headers: accessHeader(),
    })
    if (data.code !== 200) {
      throw new Error(data.message)
    }
    // 用服务端返回的权威状态覆盖本地，而不是自己 +1 / -1。
    // 这样即使本地状态是旧的（比如刚在另一台设备上点过），一按就对齐了，
    // 而且重复点赞这种"没真正生效"的请求也不会把数字改错
    topic.value.liked = data.data.liked
    topic.value.likeCount = data.data.likeCount
  } catch (e) {
    ElMessage.error('操作失败：' + (e.message || e))
  } finally {
    likeSending.value = false
  }
}

async function submitComment() {
  // 前端这一句只是为了让用户少一次往返，真正的判空在后端（全空白也算空）
  if (!commentText.value.trim()) {
    ElMessage.warning('请输入评论内容')
    return
  }
  commentSending.value = true
  try {
    const {data} = await axios.post('/api/forum/comment',
        {topicId: topic.value.id, content: commentText.value},
        {headers: accessHeader()})
    if (data.code !== 200) {
      throw new Error(data.message)
    }
    ElMessage.success('已发布')
    commentText.value = ''
    // 服务端是在**同一个事务**里插评论 + 给计数加一的，
    // 所以这里本地加一和数据库里的值必然一致，不用再拉一次详情去对
    topic.value.commentCount = (topic.value.commentCount ?? 0) + 1
    commentListRef.value?.refresh()
  } catch (e) {
    ElMessage.error('发布失败：' + (e.message || e))
  } finally {
    commentSending.value = false
  }
}

/**
 * 用 watch 而不是 onMounted。
 * <p>
 * 从 /topic/1 跳到 /topic/2 时 Vue Router 会**复用同一个组件实例**，
 * onMounted 不会再跑，页面就停在第一条帖子上 —— 而且不报错，只是"点了没反应"。
 * watch 配 immediate 把"首次进入"和"原地换 id"两种情况一起覆盖了。
 */
watch(() => route.params.id, async (id) => {
  loading.value = true
  error.value = ''
  topic.value = null
  try {
    const {data} = await axios.get(`/api/forum/topic/${id}`, {headers: accessHeader()})
    if (data.code !== 200) {
      // "帖子不存在"也是走这里：后端把它放在 body 的 code 里，HTTP 还是 200。
      // 所以 404 和网络错误在前端是同一条分支，e.message 直接就是后端那句话
      throw new Error(data.message)
    }
    topic.value = data.data
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}, {immediate: true})

/** 去作者主页。authorId 是后端 TopicDetailVo 里特意留着的字段，就是为了这个入口 */
function openAuthor() {
  router.push(`/index/user/${topic.value.authorId}`)
}
</script>

<template>
  <div style="display: flex;margin: 20px auto;gap: 20px;max-width: 900px">
    <!-- min-width: 0 是必需的：flex 子项默认 min-width:auto，
         正文里一个超宽的 <pre> 会把整个卡片撑破、把右栏挤出去 -->
    <div style="flex: 1; min-width: 0;">

      <!-- 返回键放在三态之外：加载中、出错、正常都要能回去 -->
      <div class="toolbar">
        <el-button link @click="goBack">
          <el-icon style="margin-right: 4px; vertical-align: -2px"><ArrowLeft/></el-icon>
          返回
        </el-button>
      </div>

      <card-light v-if="loading">
        <div class="status">加载中…</div>
      </card-light>

      <card-light v-else-if="error">
        <div class="status">
          <span style="color: var(--el-color-danger)">{{ error }}</span>
        </div>
      </card-light>

      <template v-else-if="topic">
        <card-light>
          <div class="title">{{ topic.title }}</div>
          <div class="meta">
            <span class="type">{{ topic.typeName }}</span>
            <!-- 点作者进主页。authorId 是 TopicDetailVo 里特意留的字段，就是为了这个入口 -->
            <span class="author" @click="openAuthor">{{ topic.authorName }}</span>
            <span>{{ formatFullTime(topic.time) }}</span>
          </div>
        </card-light>

        <card-light style="margin-top: 10px">
          <!--
            v-html 是必需的：content 是 Quill 输出的 HTML，用 {{ }} 只会显示成一串标签。
            安全性由后端 HtmlSanitizer 在**入库时**保证，所以这里可以直接渲染。

            注意样式要多带 :deep() —— v-html 插进来的元素没有 scoped 的 data-v 属性，
            写成 .content p 会静默失效（不报错，只是没样式）。原因见下面 <style> 里那段注释。
          -->
          <div class="content" v-html="topic.content"></div>

          <el-divider style="margin: 12px 0"/>

          <!--
            点赞。
            接口是 POST 和 DELETE 两个，不是"一个 toggle" ——
            客户端要表达的是"我要它变成已赞"，而不是"给我翻一下"。
            toggle 在双击、重试、开两个标签页时会翻两次翻回原样。
          -->
          <div class="actions">
            <el-button round
                       :type="topic.liked ? 'primary' : 'default'"
                       :loading="likeSending"
                       @click="toggleLike">
              <el-icon style="margin-right: 5px; vertical-align: -2px">
                <star-filled v-if="topic.liked"/>
                <star v-else/>
              </el-icon>
              {{ topic.likeCount ?? 0 }}
            </el-button>
          </div>
        </card-light>

        <!-- ---------------- 评论 ---------------- -->

        <card-light style="margin-top: 10px">
          <div class="comment-title">
            评论
            <span class="comment-count">{{ topic.commentCount ?? 0 }}</span>
          </div>

          <el-input v-model="commentText"
                    type="textarea"
                    :rows="3"
                    :maxlength="500"
                    show-word-limit
                    resize="none"
                    placeholder="说点什么…"/>
          <div class="comment-actions">
            <el-button type="primary" :loading="commentSending" @click="submitComment">发布</el-button>
          </div>
        </card-light>

        <!--
          评论列表整块交给 CommentList，和帖子列表共用同一个 InfiniteList。
          翻页、哨兵、三态一行都不用在这里写。
        -->
        <comment-list ref="commentListRef" :topic-id="topic.id" style="margin-top: 10px"/>
      </template>

    </div>

    <forum-sidebar/>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 10px;
  font-size: 14px;
}

.status {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 20px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.title {
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.meta {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.type {
  padding: 1px 6px;
  border-radius: 4px;
  background-color: var(--el-fill-color);
  font-size: 12px;
}

.author {
  &:hover {
    cursor: pointer;
    color: var(--el-color-primary);
  }
}

.comment-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

/* 点赞那一排操作 */
.actions {
  display: flex;
  align-items: center;
}

.comment-count {
  font-size: 13px;
  font-weight: normal;
  color: var(--el-text-color-secondary);
}

.comment-actions {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
}

/*
  ⚠️ 下面每一条都必须带 :deep()。

  <style scoped> 的实现是：给"模板里写出来的元素"加一个 data-v-xxx 属性，
  选择器编译成 .content[data-v-xxx] p[data-v-xxx]。
  而 v-html 插进去的节点是运行时生成的，根本没有那个属性 ——
  所以写成 .content p 的话会**静默失效**：不报错，只是没样式。

  这是 v-html 最常踩的坑，跟"样式写错"的现象一模一样，但原因完全不同。
*/
.content :deep(p) {
  margin: 0 0 10px;
  line-height: 1.8;
}

.content :deep(p:last-child) {
  margin-bottom: 0;
}

/* 帖子里的图是原始尺寸，一张截图就能把卡片撑破 */
.content :deep(img) {
  max-width: 100%;
  height: auto;
  display: block;
  margin: 10px 0;
  border-radius: 6px;
}

.content :deep(a) {
  color: var(--el-color-primary);
}

.content :deep(blockquote) {
  margin: 10px 0;
  padding-left: 10px;
  border-left: 3px solid var(--el-border-color);
  color: var(--el-text-color-secondary);
}

.content :deep(pre) {
  background-color: var(--el-fill-color);
  padding: 10px;
  border-radius: 6px;
  overflow-x: auto;
}

.content :deep(ul),
.content :deep(ol) {
  padding-left: 22px;
  margin: 0 0 10px;
}
</style>
