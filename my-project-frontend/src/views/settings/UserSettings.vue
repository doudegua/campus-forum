<script setup>

import Card from "@/components/Card.vue";
import {Message, Notebook, Refresh, Select, User} from "@element-plus/icons-vue";
import {useStore} from "@/store/index.js";
import {computed, onMounted, reactive, ref, watch} from "vue";
import {accessHeader, post} from "@/net/index.js";
import {ElMessage} from "element-plus";
import {defaultAvatar} from '@/constants/index.js'
import {compressAvatar} from "@/utils/image.js";

/**
 * 头像上传地址。**用相对路径**，不要拼 axios.defaults.baseURL。
 *
 * 原来写的是 `axios.defaults.baseURL + '/api/user/avatar'`，而 baseURL 被设成
 * `http://localhost:8080` —— 部署后那个字符串会指向访问者自己的电脑，
 * 是这个项目"线上整个前端不可用"的根因之一。现在 baseURL 不再设置，
 * 所以这里直接用相对路径：本地走 Vite proxy、线上走 nginx 反代。
 *
 * 说明：这个值其实不会被当作真实上传地址用 —— 下面 el-upload 有
 * `:http-request="uploadAvatar"`，实际请求由那个函数通过 post() 发出。
 * 但留着它是为了给 el-upload 一个合法值（默认是 '#'），
 * 并且**不能让它是 undefined**，否则读代码的人会以为这里坏了。
 */
const AVATAR_UPLOAD_URL = '/api/user/avatar'

// const defaultAvatar = 'https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png'

const store = useStore();

const baseFormRef = ref({})
const emailFormRef = ref({})

// const description = ref()

// 字段名跟后端 VO 对齐（registrationDate，不是 registerTime）。
// 顺手做了防御：/api/user/info 还没回来时它是 null，new Date(null) 会得到 Invalid Date，
// 直接渲染出来就是屏幕上写着 "Invalid Date"。
const registerTime = computed(() => {
  const value = store.user.registrationDate
  return value ? new Date(value).toLocaleDateString() : '—'
})

const baseForm = reactive({
  username: "",
  gender: 2,
  phone: "",
  qq: "",
  wechat: "",
  description: "",
  avatarUrl: ""
})

const emailForm = reactive({
  email: "",
  code: ""
})

watch(
    () => store.user,
    (user) => {
      if(!user) return
      baseForm.username = user.username || ''
      emailForm.email = user.email || ''
    },
    { immediate: true, deep: true }
)

watch(
    () => store.profile,
    (profile) => {
      if (!profile) return
      baseForm.gender = profile.gender ?? 3
      baseForm.phone = profile.phone || ''
      baseForm.qq = profile.qq || ''
      baseForm.wechat = profile.wechat || ''
      baseForm.description = profile.description || ''
      // profile 里根本没有 avatarUrl 这个字段 —— 后端给的是 avatar（图片 key）。
      // 原来那句取的是 undefined，一直悄悄兜底成 ''，所以看不出问题。
      // 真正渲染头像的是 store.profile.avatarUrl（IndexView 里按 key 拼出来的）
      baseForm.avatarUrl = profile.avatar ? `/api/image/${profile.avatar}` : ''
    },
    {
      immediate: true,
      deep: true
    }
)

const validateUsername = (rule, value, callback) => {
  if(value === '') {
    callback(new Error('请输入用户名'));
  } else if(!/^[a-zA-Z0-9\u4e00-\u9fa5]+$/.test(value)) {
    callback(new Error('用户名不能包含特殊字符，只包含中文或英文'));
  } else {
    callback();
  }
}

const rules = {
  username: [
    { validator: validateUsername, trigger: ['blur', 'change'] },
    { min: 2, max: 20, message: 'Username length must be between 2 and 20 chars.', trigger: ['blur', 'change'] }
  ], email: [
    { required: true, message: '请输入电子邮件地址', trigger: ['blur'] },
    { type:'email', message: '请输入合法的电子邮件地址', trigger: ['blur', 'change'] }
  ],
}

function updateProfile() {
  baseFormRef.value.validate(isValid =>{
    if(isValid) {
      post("/api/user/profile", baseForm, () => {
        ElMessage.success("Profile updated successfully.")

        // ⚠️ 保存成功后必须把新值**回写到 store**，否则页面看起来"没保存"。
        //
        // 数据流是单向的：
        //     store.profile ──watch──► baseForm ──v-model──► 输入框
        //                          （只有这一个方向）
        // 所以后端存成功了、输入框里也是新值，但 store.profile 还是旧的 ——
        // 页面别处读 store.profile 的地方（比如侧栏那句个人简介）就不更新，
        // 直到刷新页面重新拉一次 /api/user/profile 才变。
        //
        // 这里原来只同步了 username，还有一行被注释掉的
        //     // description.value = baseForm.description;
        // 那行就算放开也不对 —— description 是个局部 ref，不是 store 里的字段，
        // 改了它页面照样不更新。之前大概是试了没效果就注释掉了。
        //
        // 回写到 store 之后，上面那个 watch 会把新值再同步回 baseForm，
        // 于是表单和 store 两边一致。
        const p = store.profile
        p.gender = baseForm.gender
        p.phone = baseForm.phone
        p.qq = baseForm.qq
        p.description = baseForm.description

        store.user.username = baseForm.username
      })
    }
  })
}

function beforeAvatarUpload(rawFile) {
  if(rawFile.type !== 'image/jpeg' && rawFile.type !== 'image/png') {
    ElMessage.error('头像仅能为jpeg或png')
    return false
  }
  return true
}

function uploadSuccess(file) {
  ElMessage.success('头像上传成功')
  const oldUrl = store.profile.avatarUrl

  if (oldUrl?.startsWith('blob:')) {
    URL.revokeObjectURL(oldUrl)
  }

  store.profile.avatarUrl = URL.createObjectURL(file)
}

async function uploadAvatar(options) {
  if(!beforeAvatarUpload(options.file)) {return false}
  const compressedFile = await compressAvatar(options.file)

  const formData = new FormData()
  formData.append('file', compressedFile)

  post('/api/user/avatar', formData, () => {
    uploadSuccess(compressedFile)
    options.onSuccess()
  }, message => {
    ElMessage.error('头像上传失败' + message);
    options.onError(new Error(message))
  })
}

</script>

<template>
  <div style="display: flex;">
    <div class="settings-left">
      <Card :icon="User" title="Account Info Settings" desc="Edit your account here. Manipulate whether or not showcase your info in Safe Word.">
        <el-form :model="baseForm" :rules="rules" ref="baseFormRef" label-position="top" style="margin: 0 10px 10px 10px;">
          <el-form-item label="Username" prop="username">
            <el-input v-model="baseForm.username" maxlength="20"/>
          </el-form-item>
          <el-form-item label="Gender" prop="gender">
            <el-radio-group v-model="baseForm.gender">
              <el-radio :label="0">Male</el-radio>
              <el-radio :label="1">Female</el-radio>
              <el-radio :label="2">Helicopter</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="Phone" prop="phone">
            <el-input v-model="baseForm.phone" maxlength="11"/>
          </el-form-item>
          <el-form-item label="QQ" prop="qq">
            <el-input v-model="baseForm.qq" maxlength="15"/>
          </el-form-item>
          <el-form-item label="Wechat" prop="wechat">
            <el-input v-model="baseForm.wechat" maxlength="20"/>
          </el-form-item>
          <el-form-item label="Personal Desc" prop="description">
            <el-input v-model="baseForm.description" type="textarea" :rows="6" maxlength="200"/>
          </el-form-item>
          <el-button :icon="Select" @click="updateProfile">Save</el-button>
        </el-form>
      </Card>
      <card style="margin-top: 10px" :icon="Message" title="Email Settings" desc="You may manipulate your email.">
        <el-form :model="emailForm" :rules="rules" ref="emailFormRef" label-position="top" style="margin: 0 10px 10px 10px;">
          <el-form-item label="Email" prop="email">
            <el-input placeholder="请输入邮箱地址" v-model="emailForm.email"/>
          </el-form-item>
          <el-form-item label="Verify Code">
            <el-row style="width: 100%" :gutter="5">
              <el-col :span="10">
                <el-input placeholder="请获取验证码" v-model="emailForm.code"/>
              </el-col>
              <el-col :span="6" >
                <el-button type="success" v-model="emailForm.email">获取验证码</el-button>
              </el-col>
            </el-row>
          </el-form-item>
        </el-form>
        <el-button :icon="Refresh">Save</el-button>
      </card>
    </div>
    <div class="settings-right">
      <div style="position: sticky;top: 20px">
        <Card>
          <div style="text-align: center;padding: 5px 15px 0 15px">
            <div>
              <el-avatar :size="70" :src="store.profile.avatarUrl || defaultAvatar"/>
              <el-upload
                  :action="AVATAR_UPLOAD_URL"
                  :http-request="uploadAvatar"
                  :show-file-list="false"
              >
                <el-button size="small" round>上传头像</el-button>
              </el-upload>
              <div style="font-weight: bold">你好， {{store.user.username}}</div>
            </div>
            <el-divider style="margin: 10px 0;padding: 10px;font-size: 14px"></el-divider>
            <div>
              {{store.profile.description || '这个人很懒，什么都没写'}}
            </div>
          </div>
        </Card>
        <card style="margin-top: 10px">
          <div>
            账号注册时间：{{registerTime}}
          </div>
          <div style="color: gray">欢迎来到校园论坛</div>
        </card>
      </div>

    </div>
  </div>
</template>

<style scoped>
.settings-left {
  flex: 1;
  margin: 20px;
}

.settings-right {
  width: 300px;
  margin: 20px 30px 20px 0;
}
</style>
