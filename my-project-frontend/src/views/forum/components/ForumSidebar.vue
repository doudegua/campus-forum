<script setup>
import CardLight from "@/components/CardLight.vue";
import Weather from "@/components/weather/Weather.vue";
import {Calendar, CollectionTag} from "@element-plus/icons-vue";
import {computed} from "vue";

/**
 * 论坛右侧栏。抽出来是因为列表页和详情页都要用 ——
 * 它原来整块写在 Tieba.vue 里，详情页要用就只能复制一整坨。
 */
const today = computed(() => {
  const date = new Date()
  return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`
})
</script>

<template>
  <div style="width:280px">
    <div style="position: sticky; top: 20px">
      <card-light>
        <div style="font-weight: bold; margin-left: 10px">
          <el-icon style="transform: translateY(3px); font-size: 16px"><CollectionTag/></el-icon>
          公告
        </div>
        <el-divider style="margin: 10px 0"/>
        <div style="font-size: 14px; margin: 10px; color: gray">
          这里是校园论坛。发帖前先选个版块，图片不超过 900KB。
          理性发言，别发广告 —— 违规内容会被删除，账号可能被停用。
        </div>
      </card-light>
      <card-light style="margin-top: 10px">
        <div style="font-weight: bold; margin-left: 10px">
          <el-icon style="transform: translateY(2px); font-size: 14px"><calendar/></el-icon>
          天气
        </div>
        <el-divider style="margin: 10px 0"/>
        <div style="margin: 10px">
          <weather/>
        </div>
      </card-light>
      <card-light style="margin-top: 10px">
        <div class="info-text">
          <div>日期</div>
          <div>{{today}}</div>
        </div>
      </card-light>
    </div>
  </div>
</template>

<style scoped>
.info-text {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
  color: gray;
}
</style>
