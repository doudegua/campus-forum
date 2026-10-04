<script setup>
import {onMounted, reactive, ref} from "vue";
import axios from "axios";
import {ElMessage} from "element-plus";
import {accessHeader} from "@/net";
import CardLight from "@/components/CardLight.vue";
import {Lock} from "@element-plus/icons-vue";

/**
 * 隐私设置。
 * <p>
 * 只有"读自己 / 写自己"两种形态 —— 路径里没有别人的 id，所以这个接口
 * 天然不存在"看别人设置成什么样"的可能。别人的资料页拿到的是 null，
 * 而不是一个"已隐藏"的标记。
 */

const loading = ref(true)
const saving = ref(false)

/** 和后端 UpdatePrivacyVo 一一对应。默认全开，和后端建表时的 DEFAULT 1 一致 */
const form = reactive({
  showGender: true,
  showPhone: true,
  showQq: true,
  showDescription: true,
  showTopics: true,
})

/**
 * 用数组驱动渲染，而不是把 5 个 el-switch 手写 5 遍。
 * 加一项设置只需要在这儿加一行 + 后端加一列。
 */
const items = [
  {key: 'showGender', label: '性别', desc: '关掉后别人看你主页时，性别那一栏是空的'},
  {key: 'showPhone', label: '手机号', desc: '建议关掉。这是所有字段里最值得关的一个'},
  {key: 'showQq', label: 'QQ', desc: '关掉后别人看不到你的 QQ 号'},
  {key: 'showDescription', label: '个人简介', desc: '关掉后别人看不到你写的简介'},
  {key: 'showTopics', label: '我发的帖子', desc: '关掉后别人在你主页看不到你的帖子列表（帖子本身还能被搜到）'},
]

onMounted(async () => {
  try {
    const {data} = await axios.get('/api/user/privacy', {headers: accessHeader()})
    if (data.code !== 200) {
      throw new Error(data.message)
    }
    // 后端可能返回 null（还没建过资料行的情况），Object.assign 传 null 会抛
    if (data.data) {
      Object.assign(form, data.data)
    }
  } catch (e) {
    ElMessage.error('读取设置失败：' + (e.message || e))
  } finally {
    loading.value = false
  }
})

async function save() {
  saving.value = true
  try {
    // 展开成普通对象再发。直接发 reactive 代理在某些序列化路径下会带上内部字段
    const {data} = await axios.post('/api/user/privacy', {...form}, {headers: accessHeader()})
    if (data.code !== 200) {
      throw new Error(data.message)
    }
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || e))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div style="max-width: 700px; margin: 20px auto;">
    <card-light v-loading="loading">
      <div class="head">
        <el-icon style="margin-right: 6px; vertical-align: -2px"><Lock/></el-icon>
        <span class="head-title">隐私设置</span>
      </div>

      <div class="hint">
        关掉的项，别人看你主页时就是空的。他们看不出这是"没填"还是"不想给看" ——
        这是故意的，null 只说"没有内容可显示"，不透露你设置成了什么。
      </div>

      <el-divider style="margin: 12px 0"/>

      <div class="row" v-for="item in items" :key="item.key">
        <div class="row-text">
          <div class="row-label">{{ item.label }}</div>
          <div class="row-desc">{{ item.desc }}</div>
        </div>
        <el-switch v-model="form[item.key]"/>
      </div>

      <div class="actions">
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </card-light>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  font-size: 18px;
  font-weight: bold;
  color: var(--el-text-color-primary);
}

.head-title {
  line-height: 1;
}

.hint {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 10px 4px;

  & + .row {
    border-top: 1px solid var(--el-border-color-lighter);
  }
}

.row-text {
  min-width: 0;
}

.row-label {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.row-desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.actions {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
