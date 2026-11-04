<script setup>

import Card from "@/components/Card.vue";
import {Reading} from "@element-plus/icons-vue";
import {get, post} from "@/net/index.js";
import {ElMessage} from "element-plus";
import {computed, onMounted, onUnmounted, reactive, ref} from "vue";

const courses = ref([])
const loading = ref(false)
const actionState = reactive({})
const timers = new Map()

const STATUS_LABEL = {
  QUEUED: '排队中',
  SUCCESS: '选课成功',
  FAILURE: '落库失败',
  SOLD_OUT: '已抢完',
  DUPLICATE: '已经选过',
  NOT_READY: '未开放',
  NOT_STARTED: '未开始',
  CLOSED: '已结束'
}

function load() {
  loading.value = true
  get('/api/course/list', data => {
    courses.value = data ?? []
    loading.value = false
  }, () => {
    loading.value = false
  })
}

function stateOf(id) {
  return actionState[id] ?? {}
}

function statusOf(course) {
  const now = Date.now()
  if (!course.ready) return {label: '未开放', type: 'info', canGrab: false}
  if (now < new Date(course.selectStart).getTime()) {
    return {label: '未开始', type: 'warning', canGrab: false}
  }
  if (now > new Date(course.selectEnd).getTime()) {
    return {label: '已结束', type: 'info', canGrab: false}
  }
  if ((course.remaining ?? 0) <= 0) {
    return {label: '已满', type: 'danger', canGrab: false}
  }
  return {label: '可抢', type: 'success', canGrab: true}
}

function percent(course) {
  if (!course.capacity) return 0
  return Math.max(0, Math.min(100, Math.round(course.remaining / course.capacity * 100)))
}

function formatWindow(course) {
  const fmt = (v) => new Date(v).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
  })
  return `${fmt(course.selectStart)} ~ ${fmt(course.selectEnd)}`
}

function buttonLabel(course) {
  const state = stateOf(course.id)
  if (state.busy) return '处理中'
  const status = statusOf(course)
  return status.canGrab ? '立即抢课' : status.label
}

function stopTimer(id) {
  const timer = timers.get(id)
  if (timer) {
    clearInterval(timer)
    timers.delete(id)
  }
}

function finish(id, status) {
  stopTimer(id)
  const state = actionState[id]
  if (state) {
    state.busy = false
    state.message = STATUS_LABEL[status] ?? status
  }
  if (status === 'SUCCESS') {
    ElMessage.success('选课成功，已写入数据库')
    load()
  } else if (status === 'FAILURE') {
    ElMessage.error('落库失败，这次没有选上')
  }
}

function poll(id) {
  stopTimer(id)
  let attempts = 0
  const timer = setInterval(() => {
    attempts += 1
    get(`/api/course/result?courseId=${id}`, data => {
      if (data.status !== 'QUEUED') {
        finish(id, data.status)
      } else if (attempts >= 30) {
        stopTimer(id)
        const state = actionState[id]
        if (state) {
          state.busy = false
          state.message = '排队超时，请刷新查看'
        }
      }
    }, () => {
      stopTimer(id)
      const state = actionState[id]
      if (state) state.busy = false
    })
  }, 700)
  timers.set(id, timer)
}

function grab(course) {
  if (!statusOf(course).canGrab) return
  const state = actionState[course.id] ??= {}
  if (state.busy) return

  state.busy = true
  state.message = '正在抢...'

  post('/api/course/select', {courseId: course.id}, data => {
    if (data.status === 'QUEUED') {
      state.message = '已入队，等待落库'
      poll(course.id)
    } else {
      finish(course.id, data.status)
    }
  }, message => {
    state.busy = false
    state.message = message || '请求失败'
  })
}

const courseCount = computed(() => courses.value.length)

onMounted(load)
onUnmounted(() => {
  timers.forEach(timer => clearInterval(timer))
  timers.clear()
})

</script>

<template>
  <div class="course-page">
    <Card :icon="Reading" title="选课中心">
      <div class="toolbar">
        <span>{{ courseCount }} 门课程</span>
        <el-button text @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="courses" stripe>
        <el-table-column label="课程" min-width="190">
          <template #default="{row}">
            <div class="course-name">{{ row.name }}</div>
            <div class="course-meta">{{ row.teacher }} · {{ row.credit }} 学分</div>
          </template>
        </el-table-column>
        <el-table-column label="容量" width="80" prop="capacity"/>
        <el-table-column label="剩余" width="90">
          <template #default="{row}">
            <span class="remaining">{{ row.remaining }}</span>
          </template>
        </el-table-column>
        <el-table-column label="余量" min-width="135">
          <template #default="{row}">
            <el-progress :percentage="percent(row)" :show-text="false" :stroke-width="7"/>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{row}">
            <el-tag :type="statusOf(row).type" effect="light">
              {{ statusOf(row).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间窗" min-width="190">
          <template #default="{row}">
            <span class="time">{{ formatWindow(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{row}">
            <el-button
                size="small"
                :type="statusOf(row).canGrab ? 'primary' : 'info'"
                :loading="stateOf(row.id).busy"
                :disabled="!statusOf(row).canGrab || stateOf(row.id).busy"
                @click="grab(row)">
              {{ buttonLabel(row) }}
            </el-button>
            <div v-if="stateOf(row.id).message" class="action-hint">
              {{ stateOf(row.id).message }}
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && courses.length === 0" description="暂无课程"/>
    </Card>
  </div>
</template>

<style scoped>

.course-page {
  max-width: 1100px;
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

.course-name {
  font-weight: 600;
}

.course-meta {
  margin-top: 3px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.remaining {
  font-size: 18px;
  font-weight: 600;
  color: var(--el-color-primary);
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

</style>
