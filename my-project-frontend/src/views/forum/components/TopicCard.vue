<script setup>
import CardLight from "@/components/CardLight.vue";
import {useRouter} from "vue-router";
import {formatTime} from "@/utils/time.js";
import {ChatDotRound, Star, StarFilled} from "@element-plus/icons-vue";

/**
 * 列表里的一个帖子卡片。
 *
 * ⚠️ 现在这个排版是「能跑起来的最小草稿」，不是定稿。
 *
 * 里面放什么、怎么排是产品决定 —— 你看到一屏真数据之后直接改这个文件就行，
 * 不用动 Tieba.vue（那边只管列表、加载和状态）。
 *
 * 后端给的可选字段：
 *   id / title / typeName / authorName / time / commentCount / likeCount / liked
 *
 * 注意 liked 是**依赖看的人**的字段 —— 同一页列表，你和别人拿到的 liked 不一样。
 * 后端是一次查询批次算出来的（不是逐条查），前端不用管这件事。
 */
/**
 * 必须赋值给变量才拿得到。
 * defineProps 返回的对象只在**模板**里自动解构成 prop 名可用；
 * 在 <script> 里直接写 topic 是 undefined —— 而且不是编译错误，是运行时"点击没反应"。
 */
const props = defineProps({
  topic: {type: Object, required: true}
})

const router = useRouter()

/**
 * 点击进详情。用 router.push 而不是 <a href>：
 * 用 a 标签浏览器会整页重载，等于把整个 SPA 重启一遍 —— 左边栏、登录态、
 * 已经加载的列表全白费。router.push 只换 <router-view> 里的东西。
 */
function openTopic() {
  router.push(`/index/topic/${props.topic.id}`)
}
</script>

<template>
  <card-light class="topic-card" @click="openTopic">
    <div class="title">{{ topic.title }}</div>
    <div class="meta">
      <span class="type">{{ topic.typeName }}</span>
      <span>{{ topic.authorName }}</span>
      <span>{{ formatTime(topic.time) }}</span>

      <!-- 把互动数推到右边。数字为 0 就不显示 —— 满屏的"0 0"没意义 -->
      <span class="spacer"/>
      <span class="count" v-if="topic.likeCount > 0" :class="{ liked: topic.liked }">
        <el-icon>
          <star-filled v-if="topic.liked"/>
          <star v-else/>
        </el-icon>
        {{ topic.likeCount }}
      </span>
      <span class="count" v-if="topic.commentCount > 0">
        <el-icon><chat-dot-round/></el-icon>
        {{ topic.commentCount }}
      </span>
    </div>
  </card-light>
</template>

<style scoped>
.topic-card {
  /* 整个卡片可点，所以光标要变 —— 少了这句用户不知道能点 */
  cursor: pointer;
  transition: background-color 0.2s;
}

.topic-card:hover {
  background-color: var(--el-fill-color-light);
}

.title {
  font-size: 16px;
  font-weight: 500;
  color: var(--el-text-color-primary);
  /* 标题太长就截断，别把卡片撑破 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

/* 类型名做成一个小标签。用 Element Plus 的变量，深色模式自动适配 */
.type {
  padding: 1px 6px;
  border-radius: 4px;
  background-color: var(--el-fill-color);
  font-size: 12px;
}

/* 把右边的互动数推开 */
.spacer {
  flex: 1;
}

.count {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12px;

  &.liked {
    color: var(--el-color-primary);
  }
}
</style>
