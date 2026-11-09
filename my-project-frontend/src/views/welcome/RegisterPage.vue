<script setup>

import {computed, onMounted, reactive, ref} from "vue";
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

/**
 * 后端是否还允许自助注册。默认 true，问不到也当 true。
 *
 * 为什么不读构建期环境变量（VITE_DEMO_ENABLED）：
 *   那个是打包时打死的常量，只反映"构建那一刻"的环境；
 *   而注册开没开是**后端运行期**的配置（app.demo.registration-enabled）。
 *   两边各猜各的，就会出现"界面给了入口、点进去 403"的漂移。
 *   所以向后端要状态，只留一个真相来源。
 *
 * 为什么要给失败回调兜底成 true：
 *   这是个"辅助"接口，它挂了不该把注册功能一起挡掉 ——
 *   失败时退回"显示表单"，让用户自己点下去，后端仍会按真实配置拒绝。
 *   宁可多显示一个入口，也不要因为一个查询失败把功能锁死。
 */
const registrationEnabled = ref(true);

onMounted(() => {
  get('/api/auth/demo-status', (data) => {
    registrationEnabled.value = data.registrationEnabled !== false;
  }, () => {
    registrationEnabled.value = true;
  })
})

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
    <div style="font-size: 25px;font-weight: bold">
      {{ registrationEnabled ? '注册新用户' : '注册暂不可用' }}
    </div>
    <div style="font-size: 14px;color: gray">
      {{ registrationEnabled ? '欢迎注册平台。请填写信息：' : '演示站点不开放自助注册' }}
    </div>

    <!--
      演示站点：后端把注册关了，这里就别再把表单摆出来。
      不然后端 403、前端还在让人填六个格子，填完才发现发不出验证码。
      文案里要说清"下一步做什么"（去点「访客体验」），而不是只说"不可用"。
    -->
    <div v-if="!registrationEnabled" style="margin-top: 40px; display: flex; justify-content: center;">
      <el-card shadow="never" style="max-width: 380px; text-align: left;">
        <div style="font-size: 16px; font-weight: 600; margin-bottom: 10px;">
          访客演示模式已开启，注册暂不可用
        </div>
        <div style="font-size: 13px; color: #666; line-height: 1.8;">
          这是一个供访客浏览的演示站点。为了避免陌生注册与邮件滥用，
          自助注册和邮箱验证码已经关闭。<br>
          你可以回到登录页，点击「访客体验」直接进入，不需要账号。
        </div>
        <el-button style="margin-top: 18px; width: 100%;" type="warning" plain
                   @click="router.push('/')">
          返回登录页
        </el-button>
      </el-card>
    </div>

    <template v-else>
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
    </template>

    <div style="margin-top: 20px">
      <span style="font-size: 14px;line-height: 15px;color: gray">已有账号？</span>
      <el-link style="translate: 0 -1px" @click="router.push('/')">立即登录</el-link>
    </div>
  </div>
</template>

<style scoped>

</style>