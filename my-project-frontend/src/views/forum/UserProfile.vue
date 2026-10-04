<script setup>
import {computed, ref, watch} from "vue";
import {useRoute} from "vue-router";
import axios from "axios";
import {accessHeader} from "@/net";
import CardLight from "@/components/CardLight.vue";
import ForumSidebar from "@/views/forum/components/ForumSidebar.vue";
import TopicList from "@/views/forum/components/TopicList.vue";
import {ArrowLeft} from "@element-plus/icons-vue";
import {defaultAvatar} from "@/constants/index.js";
import {formatFullTime} from "@/utils/time.js";
import {useGoBack} from "@/utils/navigation.js";

/**
 * 别人的主页。
 * <p>
 * 这一页本身就是"复用"的证明：资料卡是新写的，帖子列表整块是 TopicList，
 * 右侧栏整块是 ForumSidebar。真正属于这个页面的代码只有下面这点。
 */

const route = useRoute()
const goBack = useGoBack()

const profile = ref(null)
const loading = ref(true)
const error = ref('')

/** 性别口径和设置页的 radio 一致：0=男, 1=女, 2=武装直升机 */
const GENDER_TEXT = {0: '男', 1: '女', 2: '武装直升机'}

/** 和后端 UserProfileVo 对上：字段为 null 表示"没填"或"不给看"，这里一律不显示 */
const fields = computed(() => {
  const p = profile.value
  if (!p) return []
  return [
    {label: '性别', value: GENDER_TEXT[p.gender] ?? ''},
    {label: '简介', value: p.description},
    {label: '手机号', value: p.phone},
    {label: 'QQ', value: p.qq},
    {label: '注册时间', value: formatFullTime(p.registrationDate)},
  ].filter(item => item.value)
})

/**
 * 和详情页同样的道理：从 A 的主页点到 B 的主页时，Vue Router 复用同一个组件实例，
 * onMounted 不会再跑，页面就停在 A 上。watch 配 immediate 覆盖两种情况。
 */
watch(() => route.params.id, async (id) => {
  loading.value = true
  error.value = ''
  profile.value = null
  try {
    const {data} = await axios.get(`/api/user/${id}`, {headers: accessHeader()})
    if (data.code !== 200) {
      // "用户不存在"也走这条分支：后端把它放在 body 的 code 里，HTTP 还是 200
      throw new Error(data.message)
    }
    profile.value = data.data
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}, {immediate: true})

/**
 * 列表条件。
 * <p>
 * 从 A 的主页跳到 B 的主页时这个对象的内容会变，TopicList 自己 watch 到就重拉了 ——
 * 这里不需要写"先清空再请求"。
 * <p>
 * route.params.id 是字符串（URL 里的一切都是字符串），转成数字再传。
 */
const listQuery = computed(() => ({uid: Number(route.params.id)}))
</script>

<template>
  <div style="display: flex;margin: 20px auto;gap: 20px;max-width: 900px">
    <div style="flex: 1; min-width: 0;">
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

      <template v-else-if="profile">
        <card-light>
          <div class="profile">
            <el-avatar :size="64" :src="profile.avatar ? `/api/image/${profile.avatar}` : defaultAvatar"/>

            <div class="profile-text">
              <div class="name-row">
                <span class="name">{{ profile.username }}</span>
                <!-- 角色。ADMIN 才显示，普通用户标个"管理员"没意义 -->
                <span class="role" v-if="profile.role && profile.role !== 'user'">{{ profile.role }}</span>
              </div>

              <!--
                只渲染有值的字段。null 的项直接不出现 ——
                "未填写"和"设置了不公开"在界面上长得一样，这是后端刻意设计的结果
                （区分开就等于告诉访问者"他填了但不想给你看"）
              -->
              <div class="field" v-for="item in fields" :key="item.label">
                <span class="field-label">{{ item.label }}</span>
                <span class="field-value">{{ item.value }}</span>
              </div>
            </div>
          </div>
        </card-light>

        <!--
          空状态的文案要中性：不能写"他还没发过帖子"，因为那有可能是
          show_topics 关了 —— 后端那时也返回空列表，前端分不出来。
          写死"还没发过"就是在替访问者下一个后端没告诉它的结论。
        -->
        <topic-list :query="listQuery" style="margin-top: 10px">
          <template #empty>暂无内容</template>
        </topic-list>
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

.profile {
  display: flex;
  gap: 16px;
  padding: 6px 4px;
}

.profile-text {
  min-width: 0;
  flex: 1;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.name {
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.role {
  padding: 1px 6px;
  border-radius: 4px;
  background-color: var(--el-fill-color);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.field {
  margin-top: 6px;
  display: flex;
  gap: 10px;
  font-size: 13px;
}

.field-label {
  flex: none;
  width: 60px;
  color: var(--el-text-color-secondary);
}

.field-value {
  min-width: 0;
  color: var(--el-text-color-primary);
  word-break: break-word;
}
</style>
