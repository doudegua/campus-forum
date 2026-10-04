<script setup>

import {computed, reactive, ref} from "vue";
import {EditPen, Lock, Message, User} from "@element-plus/icons-vue";
import router from "@/router/index.js";
import {get, post} from "@/net/index.js";
import {ElMessage} from "element-plus";
import {useCountdown} from "@/utils/useCountdown.js";

// 读秒逻辑抽到 useCountdown 里了 —— 原来这里自己写 setInterval，
// 而那个实现不会在 0 处停下，减到负数后 `:disabled="coldTime"` 恒为真，
// 导致读秒结束后按钮永久禁用。详见 utils/useCountdown.js 的说明。
const {coldTime, isCounting, start: startCountdown} = useCountdown(60);

const formRef = ref(null);

const form = reactive({
  username: "",
  password: "",
  password_confirmation: "",
  email: "",
  code: "",
})

const validateUsername = (rule, value, callback) => {
  if(value === '') {
    callback(new Error('请输入用户名'));
  } else if(!/^[a-zA-Z0-9\u4e00-\u9fa5]+$/.test(value)) {
    callback(new Error('用户名不能包含特殊字符，只包含中文或英文'));
  } else {
    callback();
  }
}

const validatePassword = (rule, value, callback) => {
  if(value === '') {
    callback(new Error('请再次输入密码'));
  } else if(value !== form.password) {
    callback(new Error('密码不一致'));
  } else {
    callback();
  }
}

const rule = {
  username: [
    { validator: validateUsername, trigger: ['blur', 'change'] },
  ],
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

function askCode() {
  if(isEmailValid) {
    // ⚠️ 注意：倒计时在**请求成功之后**才开始，不在点按钮时就开始。
    //
    // 原来的写法是先把 coldTime 设成 60、再发请求，请求失败时把它清零。
    // 看起来也行，但有个漏洞：请求失败到回调执行之间，按钮一直显示"请等待60秒"，
    // 而其实什么都没发出去。而且失败路径要记得清零，漏一处就卡住。
    //
    // 放到成功回调里就没有这个问题：**只有真的发出去了才读秒**。
    get(`/api/auth/ask-code?email=${form.email}&type=register`, () => {
      ElMessage.success(`验证码已发送到邮箱 ${form.email}，请注意查收`);
      startCountdown();
    }, (message) => {
      ElMessage.warning(message);
    })
  } else {
    ElMessage.warning("请输入正确的电子邮件");
  }
}

const isEmailValid = computed(() =>
    /^(([^<>()[\]\\.,;:\s@"]+(\.[^<>()[\]\\.,;:\s@"]+)*)|(".+"))@((\[[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\])|(([a-zA-Z\-0-9]+\.)+[a-zA-Z]{2,}))$/.test(form.email)
)

function register() {
  formRef.value.validate((valid) => {
    if(valid) {
      post(`/api/auth/register`, {...form}, () => {
        ElMessage.success('注册成功，欢迎加入');
        router.push('/');
      });
    } else {
      ElMessage.warning('请完整填写表单内容')
    }
  })
}

</script>

<template>
  <div style="text-align: center;margin: 50px 20px 0;">
    <div style="font-size: 25px;font-weight: bold">注册新用户</div>
    <div style="font-size: 14px;color: gray">欢迎注册平台。请填写信息：</div>
    <div style="margin-top: 50px">
      <el-form :model="form" :rules="rule" ref="formRef">
        <el-form-item prop="username">
          <el-input v-model="form.username" maxlength="20" type="text" placeholder="用户名">
            <template #prefix>
              <el-icon><User/></el-icon>
            </template>
          </el-input>
        </el-form-item>
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
        <el-form-item prop="email">
          <el-input v-model="form.email" maxlength="256" type="text" placeholder="电子邮件地址">
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
              <!--
                用 isCounting（明确的布尔值）而不是直接判断 coldTime。
                原来写的是 `|| coldTime`，而 coldTime 减到负数后是 truthy ——
                读秒结束按钮也永远点不动。
              -->
              <el-button @click="askCode" :disabled="!isEmailValid || isCounting" type="warning">
                {{ isCounting ? `请等待${coldTime}秒` : '获取验证码' }}
              </el-button>
            </el-col>
          </el-row>
        </el-form-item>
      </el-form>
    </div>
    <div style="margin-top: 80px">
      <el-button @click="register" style="width: 270px" type="warning" plain>立即注册</el-button>
    </div>
    <div style="margin-top: 20px">
      <span style="font-size: 14px;line-height: 15px;color: gray">已有账号？</span>
      <el-link style="translate: 0 -1px" @click="router.push('/')">立即登录</el-link>
    </div>
  </div>
</template>

<style scoped>

</style>