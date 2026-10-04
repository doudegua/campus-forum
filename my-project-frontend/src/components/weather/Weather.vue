<script setup>
import {computed, onMounted, ref} from "vue";
import axios from "axios";
import {accessHeader} from "@/net";

// 拿不到浏览器定位时的兜底坐标（重庆沙坪坝）。想固定看某个城市就改这里。
const FALLBACK = {lon: 106.46, lat: 29.54};

// 整个响应原样存下来，模板里再取需要的部分
const weather = ref(null);

/**
 * 数据还没拿到时给卡片盖一层 el-loading。
 * 成功和失败都会关掉它 —— 一个永远转下去的圈，用户没法区分
 * "还在加载"和"早就失败了"，比占位符更像坏了。
 */
const loading = ref(false);

const now = computed(() => weather.value?.now ?? null);
const place = computed(() => weather.value?.location ?? null);

const temperature = computed(() => {
  const v = now.value?.temperature?.value;
  return v == null ? '--' : Math.round(v);
});

const conditionText = computed(() => now.value?.condition?.text ?? '--');

const conditionIcon = computed(() => `qi-${now.value?.condition?.code ?? '999'}`);

// GeoAPI 里 adm2 是市、name 是区县；直辖市没有 adm2 时退回 adm1
const city = computed(() => place.value?.adm2 || place.value?.adm1 || '--');
const district = computed(() => place.value?.name ?? '--');

// 固定渲染 5 格，没数据时也占位，避免卡片高度跳动
const slots = computed(() => {
  const list = weather.value?.hourly ?? [];
  return Array.from({length: 5}, (_, i) => {
    const hour = list[i];
    if (!hour) {
      return {label: '--', icon: 'qi-999', temperature: '--'};
    }
    return {
      // forecastTime 是 UTC。这里用浏览器本地时区换算，所以显示的是"看的人所在时区"的小时。
      // 如果以后要看外地城市的天气，得改成让后端返回 localTime=true。
      label: `${new Date(hour.forecastTime).getHours()}时`,
      icon: `qi-${hour.condition?.code ?? '999'}`,
      temperature: hour.temperature?.value == null ? '--' : Math.round(hour.temperature.value),
    };
  });
});

async function load(lon, lat) {
  const url = `/api/weather?lon=${lon}&lat=${lat}`;
  loading.value = true;
  try {
    // 直接走 axios，不用 @/net 的 get：get 在网络层出错时只调它自己的默认处理器，
    // 成功/失败回调都不会触发，我这边就永远等不到结束 —— loading 会一直转下去。
    const {data} = await axios.get(url, {headers: accessHeader()});
    if (data.code !== 200) {
      throw new Error(data.message);
    }
    weather.value = data.data;
  } catch (e) {
    // 这是个装饰性卡片，失败就安静地退回占位符，不弹 toast 打扰。
    // 但日志要够详细，否则排查时分不清"没发请求"和"请求被拒"。
    console.warn(`天气获取失败 ${url}：${e.message || e}`);
  } finally {
    // 成功失败都要关掉，否则失败时圈会一直转
    loading.value = false;
  }
}

onMounted(() => {
  // 要立刻用兜底坐标发请求，不能让数据依赖定位结果。
  // 定位会弹权限框，只要用户不点，getCurrentPosition 的两个回调都不会触发
  // （timeout 只在授权之后才开始计时），请求就永远不会发出去，卡片一直空着。
  // 装饰性卡片宁可先显示兜底城市，拿到定位后再改。
  load(FALLBACK.lon, FALLBACK.lat);
  refineByGeolocation();
});

/** 定位成功且和兜底坐标不在同一格时，用真实位置重新取一次 */
function refineByGeolocation() {
  if (!navigator.geolocation) {
    return;
  }
  navigator.geolocation.getCurrentPosition(
      position => {
        const {longitude, latitude} = position.coords;
        // 后端缓存键量化到 1 位小数，同一格就没必要再请求一次，也避免卡片闪一下
        if (cell(longitude) === cell(FALLBACK.lon) && cell(latitude) === cell(FALLBACK.lat)) {
          return;
        }
        load(longitude, latitude);
      },
      () => {
        // 拒绝授权或定位失败：保持兜底数据，不用做任何事
      },
      {timeout: 5000}
  );
}

/** 和后端 String.format("%.1f") 对齐 */
function cell(value) {
  return Math.round(value * 10) / 10;
}
</script>

<template>
  <!-- min-height 是为了加载中时卡片不塌下去：内容还没渲染，高度会是 0，
       那样转圈会挤在一个很窄的条里 -->
  <div v-loading="loading" style="min-height: 100px">
    <div style="display: flex;justify-content: space-between;margin: 10px 8px">
      <div style="font-size: 40px;color: gray">
        <i :class="conditionIcon"></i>
      </div>
      <div style="font-weight: bold;text-align: center">
        <div style="font-size: 25px;">{{ temperature }}</div>
        <div style="font-size: 15px;">{{ conditionText }}</div>
      </div>
      <div style="margin-top:1px">
        <div style="margin-left: 10px">{{ city }}</div>
        <div style="margin: 4px;color: gray">{{ district }}</div>
      </div>
    </div>
    <el-divider style="margin: 0px 0"/>
    <div style="display: grid;grid-template-columns: repeat(5, 1fr);text-align: center">
      <div v-for="(item, index) in slots" :key="index">
        <div style="font-size: 13px">{{ item.label }}</div>
        <div style="font-size: 20px">
          <i :class="item.icon"/>
        </div>
        <div style="font-size: 13px">{{ item.temperature }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
</style>
