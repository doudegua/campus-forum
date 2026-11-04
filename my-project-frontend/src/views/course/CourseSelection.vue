<script setup>

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
    <div class="hero">
      <div>
        <div class="eyebrow">COURSE SELECTION</div>
        <h1>选课中心</h1>
        <p>每个账号同一门课只占一个名额，抢到后会异步落库。</p>
      </div>
      <div class="hero-side">
        <span class="course-count">{{ courseCount }} 门课程</span>
        <el-button text @click="load">刷新</el-button>
      </div>
    </div>

    <div v-loading="loading" class="course-grid">
      <el-card v-for="course in courses" :key="course.id" class="course-card" shadow="hover">
        <div class="card-head">
          <div>
            <h3>{{ course.name }}</h3>
            <div class="meta">{{ course.teacher }} · {{ course.credit }} 学分</div>
          </div>
          <el-tag :type="statusOf(course).type" effect="light">
            {{ statusOf(course).label }}
          </el-tag>
        </div>

        <div class="numbers">
          <div>
            <b>{{ course.remaining }}</b>
            <span>剩余 / {{ course.capacity }}</span>
          </div>
          <div class="percent">{{ percent(course) }}%</div>
        </div>
        <el-progress :percentage="percent(course)" :show-text="false" :stroke-width="7"/>

        <div class="time">
          <span>选课时间</span>
          <strong>{{ formatWindow(course) }}</strong>
        </div>

        <div class="card-foot">
          <el-button
              type="primary"
              :loading="stateOf(course.id).busy"
              :disabled="!statusOf(course).canGrab || stateOf(course.id).busy"
              @click="grab(course)">
            立即抢课
          </el-button>
          <span class="hint">{{ stateOf(course.id).message || '名额仅供参考，以接口结果为准' }}</span>
        </div>
      </el-card>
    </div>

    <el-empty v-if="!loading && courses.length === 0" description="暂无课程"/>
  </div>
</template>

<style scoped>

.course-page {
  padding: 22px 26px 40px;
}

.hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 20px;
  padding: 26px 30px;
  margin-bottom: 22px;
  border-radius: 18px;
  color: #fff;
  background: linear-gradient(120deg, #3b4cca 0%, #5b4bd6 45%, #8e5bd6 100%);
  box-shadow: 0 14px 35px rgba(59, 76, 202, .22);
}

.eyebrow {
  font-size: 11px;
  letter-spacing: 2px;
  opacity: .72;
  margin-bottom: 8px;
}

.hero h1 {
  margin: 0;
  font-size: 30px;
  letter-spacing: 1px;
}

.hero p {
  margin: 8px 0 0;
  opacity: .8;
  font-size: 13px;
}

.hero-side {
  display: flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}

.course-count {
  font-size: 13px;
  opacity: .85;
}

.hero :deep(.el-button) {
  color: #fff;
}

.course-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 18px;
}

.course-card {
  border-radius: 16px;
  border: 1px solid var(--el-border-color-lighter);
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.card-head h3 {
  margin: 0 0 6px;
  font-size: 18px;
}

.meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.numbers {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin: 22px 0 10px;
}

.numbers b {
  font-size: 30px;
  line-height: 1;
  color: var(--el-color-primary);
}

.numbers span {
  display: block;
  margin-top: 5px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.percent {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.time {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.time strong {
  font-weight: 500;
  color: var(--el-text-color-regular);
}

.card-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}

@media (max-width: 680px) {
  .hero {
    flex-direction: column;
    align-items: flex-start;
  }
}

</style>
