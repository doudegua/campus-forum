<script setup>
import {computed, ref, watch} from "vue";
import {useRoute, useRouter} from "vue-router";
import axios from "axios";
import {accessHeader} from "@/net";
import CardLight from "@/components/CardLight.vue";
import ConfirmCard from "@/components/ConfirmCard.vue";
import ForumSidebar from "@/views/forum/components/ForumSidebar.vue";
import CommentList from "@/views/forum/components/CommentList.vue";
import TopicEditor from "@/views/forum/components/TopicEditor.vue";
import {ArrowLeft, Delete, Edit, Star, StarFilled} from "@element-plus/icons-vue";
import {ElMessage} from "element-plus";
import {formatFullTime} from "@/utils/time.js";
import {useGoBack} from "@/utils/navigation.js";
import {useStore} from "@/store";
import {useForumStore} from "@/store/forum.js";

const route = useRoute()
const router = useRouter()
const goBack = useGoBack()
const store = useStore()
const forumStore = useForumStore()

const topic = ref(null)
const loading = ref(true)
/** 空串 = 没出错。非空就是给用户看的原因（含"帖子不存在"） */
const error = ref('')

/* ---------------- 编辑 / 删除的入口 ---------------- */

const editorVisible = ref(false)
const deleting = ref(false)
/** 删除确认卡片开着没有。真正发请求在 doDelete —— 这张卡片只负责"问一句" */
const deleteVisible = ref(false)

/**
 * 当前看的人是不是这条帖子的作者。
 * <p>
 * ⚠️ 这只是**藏按钮**，不是权限。真正的判断在后端（editTopic / deleteTopic
 * 里的 {@code exist.getUid().equals(uid)}）—— 任何人都能绕过界面直接发请求，
 * 前端拦不住也不该假装拦得住。
 * <p>
 * 它的全部价值只有一条：别让非作者点到一个必然失败的按钮。
 * <p>
 * store.user.id 是 IndexView 异步拉的、可能比 topic 晚到，用 computed
 * 就不用手动处理这个时序 —— 它到货时这个值自己会重算，按钮跟着出现。
 */
const isAuthor = computed(() =>
    topic.value?.authorId != null && topic.value.authorId === store.user?.id)

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
 * 评论被删掉之后：本地把计数减一，不重拉详情。
 * <p>
 * 后端的删除是在**同一个事务**里"删评论 + comment_count 减一"的，
 * 所以本地减一和数据库里的值必然一致 —— 和 submitComment 里加一是同一个道理。
 * <p>
 * 评论列表本身由 CommentList 自己 refresh，这里不用管。
 * Math.max 兜一下底：计数是个冗余列，宁可显示 0 也不显示 -1。
 */
function onCommentDeleted() {
  topic.value.commentCount = Math.max((topic.value.commentCount ?? 1) - 1, 0)
}

/* ---------------- 编辑 ---------------- */

/**
 * 保存编辑。
 * <p>
 * 抽屉只负责"把用户改成什么样"报上来，打到哪个接口、存完刷新什么是这一页的事 ——
 * 所以它把 id 和 topic 一起传过来。发帖那条路（Tieba.vue）收到的是 id=null。
 */
async function saveEdit({id, topic: payload, done}) {
  try {
    const {data} = await axios.post(`/api/forum/topic/${id}`, payload, {headers: accessHeader()})
    if (data.code !== 200) {
      throw new Error(data.message)
    }
    ElMessage.success('已保存')
    // done(true) 才会关抽屉、清表单。失败时保持打开，用户改的东西还在
    done(true)
    // 论坛页被 keep-alive 缓存着，它记的是旧标题 —— 打个信号让它下次回来时重拉。
    // 这里够不到那个列表组件，也不该为了够到它把引用层层透出去
    forumStore.markTopicListStale()
    // 详情页自己也要重拉（标题、正文、类型都可能变了），
    // 而路由 id 没变、上面那个 watch 不会触发，只能显式调一次。
    // silent：用户正看着内容，不该闪一下"加载中"再回来
    await loadTopic(id, {silent: true})
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || e))
    done(false)
  }
}

/* ---------------- 删除 ---------------- */

/**
 * 点垃圾桶：只把确认卡片叫出来，一行数据都不动。
 * <p>
 * "问"和"做"分成两个函数，是因为卡片可能被取消 —— 取消的路径
 * （点遮罩 / 点取消 / 按 ESC）由 ConfirmCard 内部处理，父组件只在
 * @confirm 里接"他真的确认了"这一件事。
 * 合成一个函数的话，就得在里面区分"这次调用是问还是做"，那是把
 * 两种语义硬塞进一个入口。
 */
function confirmDelete() {
  deleteVisible.value = true
}

/** 确认卡片里点了"删除"，才真的发请求。卡片自己会关掉，这里不管它的开关 */
async function doDelete() {
  if (deleting.value) {
    return
  }
  deleting.value = true
  try {
    const {data} = await axios.delete(`/api/forum/topic/${topic.value.id}`, {headers: accessHeader()})
    if (data.code !== 200) {
      throw new Error(data.message)
    }
    ElMessage.success('已删除')
    // ⚠️ 顺序要紧：必须在 goBack() 之前打信号。
    // 反过来会被列表页的 onActivated 抢在前面消费掉 —— 那时信号还是 false，
    // 列表就不刷了，而这是最难查的那种"代码看起来完全正确"的 bug
    forumStore.markTopicListStale()
    // 回上一页。goBack 自己处理"历史里没有上一页"的情况（直接打开链接进来的），
    // 那时它会 push 回论坛页，而不是什么都不做
    goBack()
  } catch (e) {
    // 失败就留在这儿：帖子还在，用户看得见自己删的是哪一条，也好重试。
    // 这时候**不能** goBack —— 那等于告诉他删成功了
    ElMessage.error('删除失败：' + (e.message || e))
  } finally {
    deleting.value = false
  }
}

/**
 * 拉详情。
 * <p>
 * 抽成函数而不是内联在 watch 里，是因为**保存编辑之后要再拉一次**：
 * 标题/正文都可能变了，而路由 id 没变，watch 不会触发。
 * <p>
 * @param silent 静默重拉 —— 不清空当前内容、不显示"加载中"、失败也不切成错误页。
 *               保存成功后的回读走这条路：用户正看着内容，
 *               让它先白屏再变回来是没必要的
 */
async function loadTopic(id, {silent = false} = {}) {
  if (!silent) {
    loading.value = true
    error.value = ''
    topic.value = null
  }
  try {
    const {data} = await axios.get(`/api/forum/topic/${id}`, {headers: accessHeader()})
    if (data.code !== 200) {
      // "帖子不存在"也是走这里：后端把它放在 body 的 code 里，HTTP 还是 200。
      // 所以 404 和网络错误在前端是同一条分支，e.message 直接就是后端那句话
      throw new Error(data.message)
    }
    topic.value = data.data
  } catch (e) {
    if (silent) {
      // 保存本身是成功的，只是回读失败。不把整页切成错误页 ——
      // 那等于告诉用户"什么都没保存上"，而事实正好相反
      ElMessage.warning('已保存，但页面内容没能刷新：' + (e.message || e))
    } else {
      error.value = e.message || '加载失败'
    }
  } finally {
    if (!silent) {
      loading.value = false
    }
  }
}

/**
 * 用 watch 而不是 onMounted。
 * <p>
 * 从 /topic/1 跳到 /topic/2 时 Vue Router 会**复用同一个组件实例**，
 * onMounted 不会再跑，页面就停在第一条帖子上 —— 而且不报错，只是"点了没反应"。
 * watch 配 immediate 把"首次进入"和"原地换 id"两种情况一起覆盖了。
 */
watch(() => route.params.id, (id) => loadTopic(id), {immediate: true})

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
          <!--
            标题和"作者操作"分两栏。min-width: 0 是必需的：
            flex 子项默认 min-width: auto，一个不含空格的长标题会把
            右边那两个按钮挤出卡片外面 —— 而且不报错，只是"按钮不见了"
          -->
          <div class="title-row">
            <div class="title-main">
              <div class="title">{{ topic.title }}</div>
              <div class="meta">
                <span class="type">{{ topic.typeName }}</span>
                <!-- 点作者进主页。authorId 是 TopicDetailVo 里特意留的字段，就是为了这个入口 -->
                <span class="author" @click="openAuthor">{{ topic.authorName }}</span>
                <span>{{ formatFullTime(topic.time) }}</span>
              </div>
            </div>

            <!--
              只有作者本人看得到这两个按钮。这只是"藏起来"，不是权限 ——
              真正的判断在后端，谁都能绕过界面直接发请求。
              它的价值是别让非作者点到一个必然失败的按钮
            -->
            <div v-if="isAuthor" class="owner-actions">
              <!-- 纯图标按钮，所以补 tooltip 和 aria-label：
                   图标本身说不清"编辑"还是"换个主题色"，读屏软件也念不出来 -->
              <el-tooltip content="编辑" placement="top">
                <el-button link aria-label="编辑帖子" @click="editorVisible = true">
                  <el-icon><Edit/></el-icon>
                </el-button>
              </el-tooltip>
              <el-tooltip content="删除" placement="top">
                <el-button link type="danger" aria-label="删除帖子"
                           :loading="deleting" @click="confirmDelete">
                  <el-icon><Delete/></el-icon>
                </el-button>
              </el-tooltip>
            </div>
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

          @deleted 是它删掉一条评论之后回头喊一声 —— 上面那个计数归这一页管，
          列表内部的情况它自己清楚（它自己 refresh）
        -->
        <comment-list ref="commentListRef" :topic-id="topic.id" style="margin-top: 10px"
                      @deleted="onCommentDeleted"/>
      </template>

    </div>

    <forum-sidebar/>
  </div>

  <!--
    编辑抽屉复用发帖那一个组件（TopicEditor）。
    :topic 非 null 就切进编辑模式 —— 标题文案、提交接口、以及**草稿的读写**
    都由它决定。草稿隔离那条最要紧，见 TopicEditor 里 saveDraft 上面那段。
  -->
  <topic-editor v-model="editorVisible" :topic="topic" @submit="saveEdit"/>

  <!--
    删除确认。不用 ElMessageBox：那张框的位置和动画都是写死的，
    这里的卡片要从画面下方升到正中，曲线得自己定（见 ConfirmCard）
  -->
  <confirm-card v-model="deleteVisible"
                title="确定删除这条帖子？"
                message="删除后不可恢复。这条帖子下的评论和点赞也会一起删掉。"
                confirm-text="删除"
                @confirm="doDelete"/>
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

.title-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

/*
 * min-width: 0 是这条布局里唯一没法省的一行。
 * flex 子项的 min-width 默认是 auto —— 它不肯比自己的内容更窄，
 * 于是一个长标题会把 .owner-actions 顶出卡片右边界（按钮还在 DOM 里，
 * 只是看不见了，所以排查起来像"按钮没渲染"）。
 * 设成 0 才允许这一栏被压缩，标题自己换成两行。
 */
.title-main {
  flex: 1;
  min-width: 0;
}

/* 按钮不参与压缩，永远在右上角占住自己的位置 */
.owner-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.title {
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  /* 一长串没有空格的字符（URL、代码）否则会溢出卡片 */
  word-break: break-word;
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
