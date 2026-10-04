<script setup>

</script>

<template>
  <div style="width:100vw;height:100vh;overflow:hidden;display:flex">
    <div style="flex: 1;background-color: black">
      <!--
        背景图用**本地文件**（public/icon1.jpg → 根路径 /icon1.jpg），不用外链。
        原来指向 cube.elemecdn.com —— 那是小米的 CDN，而**它现在已经挂了**
        （实测 HTTP 000，一个字节都下不来），所以登录页背景一直是黑的。

        这和 constants/index.js 里头像那处是同一个教训，那边已经改成本地文件了：
          第三方 CDN 哪天挂掉/被限流，你的页面就跟着坏，而你什么都做不了。
        静态资源放 public/ 下由自己的 nginx 提供，就没有这个风险。
      -->
      <el-image style="width: 100%;height: 100%" fit="cover" src="/icon1.jpg"/>
    </div>
    <div class="welcome-title">
      <div style="font-size: 30px;font-weight: bold">欢迎来到校园论坛</div>
      <!--
        三字一组的排版保留（原来这里是某高校的校训）。
        换成了通用的校园社区说明，因为原校训会让人以为这个论坛属于那所学校 ——
        它只是个人学习项目，挂一个具体学校的校训属于"借名"，也不准确。
      -->
      <div style="margin-top: 8px">记录校园&nbsp;&nbsp;&nbsp;/&nbsp;&nbsp;&nbsp;分享日常&nbsp;&nbsp;&nbsp;/&nbsp;&nbsp;&nbsp;交流想法&nbsp;&nbsp;&nbsp;/&nbsp;&nbsp;&nbsp;结识同伴</div>
      <div style="margin-top: 5px">认真发帖&nbsp;&nbsp;友善回帖</div>
    </div>
    <div class="right-card">
      <router-view v-slot="{ Component }">
        <transition name="el-fade-in-linear" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </div>
  </div>
</template>

<style scoped>
.welcome-title {
  position: absolute;
  bottom: 30px;
  left: 30px;
  color: white;
  text-shadow: 0 0 10px black;
}
.preserve-whitespace {
  white-space: pre-wrap;
}
.right-card {
  width: 400px;
  z-index: 1;
  background-color: var(--el-bg-color);
}
</style>