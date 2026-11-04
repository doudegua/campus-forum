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

const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref(null)
const createForm = reactive({
  name: '',
  teacher: '',
  credit: 3,
  capacity: 40,
  selectStart: '',
  selectEnd: ''
})
const createRules = {
  name: [{required: true, message: '请输入课程名', trigger: 'blur'}],
  credit: [{required: true, message: '请输入学分', trigger: 'change'}],
  capacity: [{required: true, message: '请输入容量', trigger: 'change'}],
  selectStart: [{required: true, message: '请选择开始时间', trigger: 'change'}],
  selectEnd: [{required: true, message: '请选择结束时间', trigger: 'change'}]
}

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

function openCreate() {
  createForm.name = ''
  createForm.teacher = ''
  createForm.credit = 3
  createForm.capacity = 40
  createForm.selectStart = ''
  createForm.selectEnd = ''
  createVisible.value = true
}

function submitCreate() {
  createFormRef.value?.validate(valid => {
    if (!valid) return
    creating.value = true
    post('/api/admin/course', {
      name: createForm.name,
      teacher: createForm.teacher,
      credit: createForm.credit,
      capacity: createForm.capacity,
      selectStart: createForm.selectStart,
      selectEnd: createForm.selectEnd
    }, id => {
      creating.value = false
      createVisible.value = false
      ElMessage.success(`课程已创建，id=${id}`)
      load()
    }, message => {
      creating.value = false
      ElMessage.warning(message || '创建失败')
    })
  })
}

</script>

<template>
  <div class="admin-page">
    <Card :icon="Setting" title="课程管理">
      <div class="toolbar">
        <span>{{ courses.length }} 门课程</span>
        <div class="toolbar-actions">
          <el-button type="primary" @click="openCreate">添加课程</el-button>
          <el-button text @click="load">刷新</el-button>
        </div>
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
    </Card>

    <el-dialog v-model="createVisible" title="添加课程" width="480px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="课程名" prop="name">
          <el-input v-model="createForm.name" maxlength="128" placeholder="例如：数据结构与算法"/>
        </el-form-item>
        <el-form-item label="教师" prop="teacher">
          <el-input v-model="createForm.teacher" maxlength="64" placeholder="选填"/>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="学分" prop="credit">
              <el-input-number v-model="createForm.credit" :min="0" :max="99" :step="0.5"
                               :precision="1" style="width: 100%"/>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="容量" prop="capacity">
              <el-input-number v-model="createForm.capacity" :min="1" :max="10000"
                               style="width: 100%"/>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="选课开始时间" prop="selectStart">
          <el-date-picker v-model="createForm.selectStart" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%"/>
        </el-form-item>
        <el-form-item label="选课结束时间" prop="selectEnd">
          <el-date-picker v-model="createForm.selectEnd" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%"/>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
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

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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
