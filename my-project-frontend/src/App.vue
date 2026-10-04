<script setup>

import {useDark, useToggle} from "@vueuse/core";

useDark({
  selector: 'html',
  attribute: 'class',
  valueDark: 'dark',
  valueLight: 'light',
})

useDark({
  onChanged(dark) {useToggle(dark)}
})
</script>

<template>
  <div>
    <router-view></router-view>
  </div>
</template>

<style>
html:root {
  --el-border-radius-base: 12px;
  --el-border-radius-round: 12px;
}

.el-dropdown__popper.el-popper,
.light-card-popper.el-popper {
  --el-popper-border-radius: 17px;
  --el-border-radius-base: 17px;
  border: none;
  background-color: var(--el-bg-color);
  overflow: hidden;
}

.el-dropdown__popper .el-dropdown-menu,
.light-card-popper .el-select-dropdown {
  background-color: var(--el-bg-color);
}

.el-dropdown__popper .el-popper__arrow,
.light-card-popper .el-popper__arrow {
  display: none;
}
</style>
