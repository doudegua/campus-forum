<script setup>

</script>

<template>
  <div style="width:100vw;height:100vh;overflow:hidden;display:flex">
    <div style="flex: 1;background-color: black">
      <!--
        背景图用**本地文件**（public/login-bg.jpg → 根路径 /login-bg.jpg）。

        来源：原来是外链小米 CDN 的一张图（cube.elemecdn.com）。那个链接本身
        没坏 —— 实测从新加坡能下到原图（6000x4000，4.2MB），
        但**从国内访问超时**（HTTP 000）。所以对国内访问者来说它就是坏的，
        登录页背景永远是黑的。

        已经把它下下来、缩到 1920x1280（约 490KB）放进 public/ 自己托管。
        这样谁访问都拿得到，也不受第三方可用性影响。

        这和 constants/index.js 里头像那处是同一个教训：静态资源自己托管。
      -->
      <el-image style="width: 100%;height: 100%" fit="cover" src="/login-bg.jpg"/>
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