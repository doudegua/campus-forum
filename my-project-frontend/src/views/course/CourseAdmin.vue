<script setup>

import {get, post} from "@/net/index.js";
import {ElMessage} from "element-plus";
import {onMounted, reactive, ref} from "vue";

const courses = ref([])
const loading = ref(false)
const busy = reactive({})
const actionMessage = reactive({})

function load() {
  loading.value = true
  get('/api/admin/course/list', data => {
    courses.value = data ?? []
    loading.value = false
  }, () => {
    loading.value = false
  })
}

function formatWindow(course) {
  const fmt = (v) => new Date(v).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
  })
  return `${fmt(course.selectStart)} ~ ${fmt(course.selectEnd)}`
}

function preheat(course) {
  if (busy[course.id]) return
  busy[course.id] = true
  post('/api/admin/course/preheat', [{courseId: course.id}], data => {
    const result = data?.[0]
    actionMessage[course.id] = result?.status ?? 'UNKNOWN'
    busy[course.id] = false
    if (result?.status === 'OPENED') {
      ElMessage.success(`${course.name} 已开放`)
      load()
    } else if (result?.status === 'SKIPPED') {
      ElMessage.info(`${course.name} 已经是开放状态`)
    } else {
      ElMessage.warning(`${course.name}: ${result?.status ?? '未知结果'}`)
    }
  }, message => {
    busy[course.id] = false
    actionMessage[course.id] = message || '请求失败'
  })
}

function close(course) {
  if (busy[course.id]) return
  busy[course.id] = true
  post('/api/admin/course/close', [course.id], data => {
    const result = data?.[0]
    actionMessage[course.id] = result?.status ?? 'UNKNOWN'
    busy[course.id] = false
    if (result?.status === 'CLOSED') {
      ElMessage.success(`${course.name} 已关停`)
      load()
    } else if (result?.status === 'SKIPPED') {
      ElMessage.info(`${course.name} 本来就没开放`)
    } else {
      ElMessage.warning(`${course.name}: ${result?.status ?? '未知结果'}`)
    }
  }, message => {
    busy[course.id] = false
    actionMessage[course.id] = message || '请求失败'
  })
}

onMounted(load)

</script>

<template>
  <div class="admin-page">
    <div class="hero">
      <div>
        <div class="eyebrow">ADMIN · COURSE</div>
        <h1>课程管理</h1>
        <p>预热后用户才能抢；关停先摘 ready，再清 chosen。</p>
      </div>
      <el-button text @click="load">刷新</el-button>
    </div>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="courses" stripe>
        <el-table-column prop="id" label="ID" width="70"/>
        <el-table-column prop="name" label="课程" min-width="150"/>
        <el-table-column prop="teacher" label="老师" width="100"/>
        <el-table-column prop="capacity" label="容量" width="80"/>
        <el-table-column prop="selectedCount" label="DB 已选" width="95"/>
        <el-table-column prop="chosenCount" label="Redis 已占" width="105"/>
        <el-table-column prop="remaining" label="剩余" width="80"/>
        <el-table-column label="状态" width="90">
          <template #default="{row}">
            <el-tag :type="row.ready ? 'success' : 'info'" effect="light">
              {{ row.ready ? '已开放' : '未开放' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间窗" min-width="200">
          <template #default="{row}">
            <span class="time">{{ formatWindow(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{row}">
            <el-button size="small" type="primary" :loading="busy[row.id]"
                       :disabled="row.ready" @click="preheat(row)">
              预热/开放
            </el-button>
            <el-button size="small" :loading="busy[row.id]"
                       :disabled="!row.ready" @click="close(row)">
              关停
            </el-button>
            <div v-if="actionMessage[row.id]" class="action-hint">{{ actionMessage[row.id] }}</div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <div class="note">
      <strong>看数说明</strong>
      <span>DB 已选 = 已落库；Redis 已占 = 包含还在队列里、尚未落库的名额。两者短暂不一致是正常的。</span>
    </div>
  </div>
</template>

<style scoped>

.admin-page {
  padding: 22px 26px 40px;
}

.hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 20px;
  padding: 26px 30px;
  margin-bottom: 18px;
  border-radius: 18px;
  color: #fff;
  background: linear-gradient(120deg, #1f2937 0%, #334155 55%, #475569 100%);
  box-shadow: 0 14px 35px rgba(30, 41, 59, .2);
}

.eyebrow {
  font-size: 11px;
  letter-spacing: 2px;
  opacity: .68;
  margin-bottom: 8px;
}

.hero h1 {
  margin: 0;
  font-size: 28px;
}

.hero p {
  margin: 8px 0 0;
  opacity: .75;
  font-size: 13px;
}

.hero :deep(.el-button) {
  color: #fff;
}

.table-card {
  border-radius: 14px;
}

.time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.action-hint {
  margin-top: 4px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.note {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin-top: 14px;
  padding: 12px 16px;
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.note strong {
  color: var(--el-text-color-regular);
  white-space: nowrap;
}

</style>
