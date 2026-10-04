<script setup>

import CardLight from "@/components/CardLight.vue";
import {EditPen} from "@element-plus/icons-vue";
import TopicEditor from "@/views/forum/components/TopicEditor.vue";
import TopicList from "@/views/forum/components/TopicList.vue";
import ForumSidebar from "@/views/forum/components/ForumSidebar.vue";
import {ElMessage} from "element-plus";
import axios from "axios";
import {accessHeader} from "@/net";
import {computed, onMounted, ref} from "vue";

/**
 * 组件名必须显式写出来。
 * <p>
 * IndexView 里的 keep-alive 是按名字匹配的（:include="['Tieba']"），
 * 而 <script setup> 默认不产生 name —— 不写这句，include 匹配不上，
 * keep-alive 会静默地什么都不缓存，页面表现和没写一样。
 */
defineOptions({name: 'Tieba'})

const editorVisible = ref(false)

/** 拿到 TopicList 实例，发帖成功后喊它重拉 */
const topicListRef = ref(null)

/* ---------------- 类型筛选 ---------------- */

const topicTypes = ref([])
/** 选中的类型 id。null = 全部 */
const selectedType = ref(null)

onMounted(async () => {
  try {
    const {data} = await axios.get('/api/forum/topic_type', {headers: accessHeader()})
    if (data.code === 200) {
      topicTypes.value = data.data ?? []
    }
  } catch (e) {
    // 类型拉不到就退化成"只有全部"这个选项，不该因此挡住整个列表
    console.error('加载帖子类型失败', e)
  }
})

/**
 * 列表条件。
 * <p>
 * 这里就是"props 驱动"的收益：改一下 selectedType，TopicList 自己就重拉了 ——
 * 不需要在这儿写一句 `topicListRef.value.refresh()`，也就没有"忘了写"的机会。
 * <p>
 * 注意"全部"表达为**不传 types**，而不是传一个哨兵值（比如 0）。
 * 传空数组会让后端拼出 `IN ()` 这种语法错误，所以后端那边也用
 * `types == null || types.isEmpty()` 来判断"跳过这个条件"。
 */
const listQuery = computed(() =>
    selectedType.value == null ? {} : {types: [selectedType.value]})

/**
 * 发帖。done(ok) 用来告诉抽屉"服务端到底写成功了没有" ——
 * 只有成功它才会清空表单和草稿并关掉抽屉。
 * 失败时保持原样，用户写的东西还在，改一改就能重发。
 */
async function publish({topic, done}) {
  try {
    const {data} = await axios.post('/api/forum/topic', topic, {headers: accessHeader()})
    if (data.code !== 200) {
      throw new Error(data.message)
    }
    ElMessage.success('发布成功')
    done(true)
    // 新帖在已加载窗口的"上面"，只能从第一页重拉才看得到。
    // 这是个"命令"（数据没变，就是想重拉），所以走 expose 出来的方法；
    // 如果是条件变了，那该改 listQuery，列表会自己反应
    topicListRef.value?.refresh()
  } catch (e) {
    ElMessage.error('发布失败：' + (e.message || e))
    done(false)
  }
}

</script>

<template>
  <div style="display: flex;margin: 20px auto;gap: 20px;max-width: 900px">
    <div style="flex: 1;">
      <card-light>
        <div class="create-topic" @click="editorVisible = true">
          <div style="margin-left: 10px">
            <el-icon style="transform: translateY(2px);"><edit-pen/></el-icon>
            分享点什么…</div>
        </div>
      </card-light>

      <!--
        类型筛选。点一下只改 selectedType，列表自己会重拉 ——
        这里没有任何"点了之后要记得干什么"的代码。
      -->
      <div class="filter-bar">
        <span class="pill" :class="{active: selectedType === null}"
              @click="selectedType = null">全部</span>
        <span class="pill" v-for="t in topicTypes" :key="t.id"
              :class="{active: selectedType === t.id}"
              @click="selectedType = t.id">{{ t.name }}</span>
      </div>

      <!--
        列表整块交给 TopicList。这一页只负责两件事：给条件（listQuery）、
        说"空了显示什么"（#empty 插槽）。

        刷新按钮也跟着进组件了 —— 它是列表自己的功能，跟着列表走。
        这样用户页自动也有一个，不用两边各写一遍。
      -->
      <topic-list ref="topicListRef" :query="listQuery" style="margin-top: 10px">
        <template #empty>
          {{ selectedType === null ? '还没有帖子，发一个试试' : '这个分类下还没有帖子' }}
        </template>
      </topic-list>
    </div>
    <forum-sidebar/>
  </div>

  <topic-editor v-model="editorVisible" @submit="publish"/>
</template>

<style scoped>
/* 类型筛选那一排小标签 */
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  padding: 0 2px;
}

.pill {
  padding: 3px 12px;
  border-radius: 999px;
  font-size: 13px;
  cursor: pointer;
  /* 用 Element Plus 的变量，深色模式自动适配 */
  color: var(--el-text-color-secondary);
  background-color: var(--el-fill-color);
  transition: color 0.2s, background-color 0.2s;

  &:hover {
    color: var(--el-color-primary);
  }

  &.active {
    color: #fff;
    background-color: var(--el-color-primary);
  }
}

.create-topic {
  /* 用 Element Plus 的变量，别写死颜色：html.dark 会自动把 --el-fill-color
     换成 #303030、--el-text-color-secondary 换成 #a3a6ad，
     这样不用为深色模式再写一套规则。
     亮色下的值也基本一致（#f0f2f5 / #909399）。 */
  background-color: var(--el-fill-color);
  border-radius: 10px;
  height: 25px;
  font-size: 16px;
  line-height: 26px;
  padding: 10px 0;
  margin: 8px;
  color: var(--el-text-color-secondary);

  &:hover {
    cursor: pointer;
  }
}
</style>
