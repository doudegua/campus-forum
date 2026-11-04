<script setup>

import Card from "@/components/Card.vue";
import {Setting} from "@element-plus/icons-vue";
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
    <Card :icon="Setting" title="课程管理" desc="预热后用户才能抢；关停先摘 ready，再清 chosen">
      <div class="toolbar">
        <span>{{ courses.length }} 门课程</span>
        <el-button text @click="load">刷新</el-button>
      </div>

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

      <el-empty v-if="!loading && courses.length === 0" description="暂无课程"/>

      <div class="note">
        <strong>看数说明</strong>
        <span>DB 已选 = 已落库；Redis 已占 = 包含还在队列里、尚未落库的名额。两者短暂不一致是正常的。</span>
      </div>
    </Card>
  </div>
</template>

<style scoped>

.admin-page {
  max-width: 1200px;
  margin: 20px auto;
  padding: 0 10px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 0 4px 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
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
