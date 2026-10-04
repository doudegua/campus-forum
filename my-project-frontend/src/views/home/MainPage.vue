<script setup>

import Card from "@/components/Card.vue";
import {onMounted, onUnmounted, ref} from "vue";

const currentTime = ref('')
let timer

function updateTime() {
  currentTime.value = new Date().toLocaleString()
}

onMounted(() => {
  updateTime()
  timer = setInterval(updateTime, 1000)
})

onUnmounted(() => {
  clearInterval(timer)
})

</script>

<template>
  <div style="display: flex;">
    <div style="margin: 20px;">
      <Card title='欢迎来到校园论坛'>
        <div style="margin-top: 5px;">现在时间是{{ currentTime }}</div>
      </Card>
    </div>
  </div>
</template>

<style scoped>

</style>