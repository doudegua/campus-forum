<script setup>
import {Lock, User} from '@element-plus/icons-vue'
import {reactive, ref} from "vue";
import {login} from "@/net/index.js";
import router from "@/router/index.js";

const formRef = ref()

/*
 * 访客演示账号。
 *
 * 为什么账号密码可以直接写在前端：
 *   它本来就是一个**公开**账号（demo/demo123），不是什么密钥；
 *   打包进 JS 也不比写在 SQL 里更危险。真正要守住的不是账号密码，
 *   而是"没人能改掉它的密码"—— 它绑定 demo@example.com（RFC 2606 保留域名，
 *   永远收不到信），改密码必须走邮箱验证码，所以这条路是死的。
 *
 * 为什么这里调用的是正常的 login()，而不是一个 /api/auth/demo-login：
 *   免密登录接口等于后门，一旦加上，谁都能绕过密码直接拿 token。
 *   这里复用普通表单登录，后端一行认证逻辑都不用改，
 *   日后要关掉演示，只要隐藏这个按钮 + 在数据库里处理 demo 账号即可。
 */
const DEMO_USERNAME = 'demo'
const DEMO_PASSWORD = 'demo123'

/*
 * 按钮显示开关，构建时由 VITE_DEMO_ENABLED 注入。
 *
 * 默认是"显示"（只有显式写 false 才隐藏），原因很实际：
 *   Dockerfile 里 npm run build 时没有传这个变量，而 .dockerignore
 *   又把 .env 挡在镜像外。如果默认隐藏，线上构建出来的页面就永远没有入口。
 * 想关掉：本地/CI 构建前在 my-project-frontend/.env 写 VITE_DEMO_ENABLED=false，
 * 或者（Docker 场景）在构建时把变量传进去，见 .env.example 的说明。
 */
const demoEnabled = (import.meta.env.VITE_DEMO_ENABLED ?? 'true').toLowerCase() !== 'false'

const form = reactive({
  username: "",
  password: "",
  remember: false
})

const rule = {
  username: [
    {required: true, message: '请输入用户名'}
  ],
  password: [
    {required: true, message: '请输入密码'}
  ]
}

function userLogin() {
  formRef.value.validate((valid) => {
    if (valid) {
      login(form.username, form.password, form.remember, () => router.push('/index'))
    }
  })
}

function demoLogin() {
  /*
   * 直接复用 userLogin 背后的 login()：存 token、错误提示、
   * 成功回调跳 /index 全都在那里，不另写一套。
   *
   * remember 传 false（token 进 sessionStorage）：
   *   演示账号是给公网陌生人用的，很可能在共用电脑上点。
   *   关掉浏览器就让登录态消失，比留在 localStorage 里放一个星期安全。
   */
  login(DEMO_USERNAME, DEMO_PASSWORD, false, () => router.push('/index'))
}
</script>

<template>
  <div style="text-align: center;margin: 0 20px">
    <div style="margin-top: 150px;">
      <div style="font-weight: bold;font-size: 25px">
        登录
      </div>
      <div style="margin-top: 5px;color: gray;">
        请输入用户名和密码以登录
      </div>
    </div>
    <div style="margin-top: 50px;">
      <el-form :model="form" :rules="rule" ref="formRef">
        <el-form-item prop="username">
          <el-input v-model="form.username" maxlength="114" type="text" placeholder="用户名或邮箱">
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码">
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-row>
          <el-col :span="12" style="text-align: left">
            <el-form-item prop="remember">
              <el-checkbox v-model="form.remember" label="记住我" />
            </el-form-item>
          </el-col>
          <el-col :span="12" style="text-align: right">
            <el-link @click="router.push('/reset')">忘记密码？</el-link>
          </el-col>
        </el-row>
      </el-form>
    </div>
    <div style="margin-top: 20px">
      <el-button @click="userLogin" style="width: 270px" type="primary" plain>立即登录</el-button>
    </div>
    <!-- 次要入口：用 link 样式，不和主登录按钮抢视觉焦点 -->
    <div v-if="demoEnabled" style="margin-top: 14px">
      <el-button link type="primary" @click="demoLogin">访客体验</el-button>
    </div>
    <el-divider>
      <span style="font-size: 13px;color: gray">没有账号</span>
    </el-divider>
    <el-button @click="router.push('/register')" style="width: 270px" type="warning" plain>立即注册</el-button>
  </div>
</template>

<style scoped>

</style>