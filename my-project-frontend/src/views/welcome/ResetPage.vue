<script setup>

import {computed, reactive, ref} from "vue";
import {EditPen, Lock, Message} from "@element-plus/icons-vue";
import {get, post} from "@/net/index.js";
import {ElMessage} from "element-plus";
import {useCountdown} from "@/utils/useCountdown.js";

const active = ref(0);
// 和注册页共用同一份读秒实现。原来这里也是自己写 setInterval，
// 同样不在 0 处停下（虽然这页的判断写法让它没暴露成"永久禁用"，
// 但计时器会一直跑下去、重复点击还会叠加）。
const {coldTime, isCounting, start: startCountdown} = useCountdown(60);
const formRef = ref({});

const form = reactive({
  email: '',
  code: '',
  password: '',
  password_confirmation: '',
})

const validatePassword = (rule, value, callback) => {
  if(value === '') {
    callback(new Error('请再次输入密码'));
  } else if(value !== form.password) {
    callback(new Error('密码不一致'));
  } else {
    callback();
  }
}

function askCode() {
  if(isEmailValid) {
    // ⚠️ type 必须是 reset，不能是 register。
    //
    // 原来这里写的是 `type=register`，两个后果：
    //   1. 后端用它决定邮件主题和正文，于是重置密码时收到的是
    //      「欢迎注册网站 / 您的邮件注册验证码为…」—— 内容牛头不对马嘴
    //   2. 验证码本身能用（Redis 按邮箱存，和 type 无关），所以这个 bug
    //      **不会让功能失败，只会让人困惑** —— 这类 bug 最容易活很久
    //
    // 后端对 type 有 @Pattern(regexp = "(register|reset)") 校验，
    // 所以传错值不会报错、会被静默接受，只能靠人看出邮件内容不对。
    get(`/api/auth/ask-code?email=${form.email}&type=reset`, () => {
      ElMessage.success(`验证码已发送到邮箱 ${form.email}，请注意查收`);
      startCountdown();
    }, (message) => {
      ElMessage.warning(message);
    })
  } else {
    ElMessage.warning("请输入正确的电子邮件");
  }
}

const rule = {
  password: [
    { required: true, message: '请输入密码', trigger: ['blur', 'change'] },
    { min: 6, max: 29, message: '密码长度6-20字符之间', trigger: ['blur', 'change'] },
  ],
  password_confirmation: [
    { validator: validatePassword, trigger: ['blur', 'change'] },
  ],
  email: [
    { required: true, message: '请输入电子邮件地址', trigger: ['blur'] },
    { type:'email', message: '请输入合法的电子邮件地址', trigger: ['blur', 'change'] }
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: ['blur'] }
  ]
}

const isEmailValid = computed(() =>
    /^(([^<>()[\]\\.,;:\s@"]+(\.[^<>()[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/.test(form.email)
)

function confirmReset() {
  formRef.value.validate((valid) => {
    if(valid) {
      post('/api/auth/reset-confirm', {
        email: form.email,
        code: form.code,
      }, active.value++)
    }
  })
}

function doReset() {
  formRef.value.validate((valid) => {
    if(valid) {
      post('api/auth/reset-password', {...form}, () => {
        ElMessage.success('密码重置成功，请登录');
      });
    }
  })
}

</script>

<template>
  <div style="text-align: center">
    <div style="margin-top: 30px">
      <el-steps :active="active" finish-status="success" align-center>
        <el-step title="验证电子邮件"/>
        <el-step title="重置密码"/>
      </el-steps>
    </div>
    <div style="margin: 0 20px" v-if="active === 0">
      <div style="margin-top: 80px">
        <div style="font-size: 25px; font-weight: bold">重置密码</div>
        <div style="font-size: 14px; color: gray">请输入需要重置密码的电子邮件地址</div>
      </div>
      <div style="margin-top: 50px">
        <el-form :model="form" :rules="rule" ref="formRef">
          <el-form-item prop="email">
            <el-input v-model="form.email" type="email" placeholder="电子邮件地址">
              <template #prefix>
                <el-icon><Message/></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item prop="code">
            <el-row :gutter="10" style="width: 100%;">
              <el-col :span="17">
                <el-input v-model="form.code" maxlength="6" type="text" placeholder="请输入验证码">
                  <template #prefix>
                    <el-icon><EditPen/></el-icon>
                  </template>
                </el-input>
              </el-col>
              <el-col :span="5">
                <el-button @click="askCode" :disabled="!isEmailValid || isCounting" type="warning">
                  {{ isCounting ? `请等待${coldTime}秒` : '获取验证码' }}
                </el-button>
              </el-col>
            </el-row>
          </el-form-item>
        </el-form>
      </div>
      <div style="margin-top: 80px">
        <el-button @click="active++" style="width: 270px" type="warning" plain>开始重置密码</el-button>
      </div>
    </div>
    <div style="margin: 0 20px" v-if="active === 1">
      <div style="margin-top: 80px">
        <div style="font-size: 25px; font-weight: bold">重置密码</div>
        <div style="font-size: 14px; color: gray">请填写新密码，务必牢记</div>
      </div>
      <div style="margin-top: 50px">
        <el-form :model="form" :rules="rule" ref="formRef">
          <el-form-item prop="password">
            <el-input v-model="form.password" maxlength="20" type="password" placeholder="密码">
              <template #prefix>
                <el-icon><Lock/></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item prop="password_confirmation">
            <el-input v-model="form.password_confirmation" maxlength="20" type="password" placeholder="再次输入密码">
              <template #prefix>
                <el-icon><Lock/></el-icon>
              </template>
            </el-input>
          </el-form-item>
        </el-form>
      </div>
      <div style="margin-top: 80px">
        <el-button @click="doReset" style="width: 270px" type="warning" plain>立即重置密码</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>

</style>