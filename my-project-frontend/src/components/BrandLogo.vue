<script setup>
/**
 * 顶部品牌标识。
 * 图形用 currentColor，文字用 Element Plus 的文本变量，深色/浅色自动适配。
 * 只是一个占位品牌，名字定了改这里一处即可。
 */
</script>

<template>
  <div class="brand" title="校园一站 · 一站式校园生活服务平台">
    <svg class="brand-mark" viewBox="0 0 32 32" fill="none" aria-hidden="true">
      <rect x="2.5" y="2.5" width="27" height="27" rx="9"
            stroke="currentColor" stroke-width="2"/>
      <path d="M9 22V13.5L16 9l7 4.5V22"
            stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>
      <path d="M13 22v-5h6v5"
            stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>
    </svg>
    <span class="brand-name">校园一站</span>
  </div>
</template>

<style scoped>
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-primary);
}

.brand-mark {
  width: 30px;
  height: 30px;
  color: var(--el-color-primary);
  flex: none;
}

.brand-name {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: .5px;
  white-space: nowrap;
}
</style>
