<!-- src/components/Weather.vue -->
<template>
  <div class="qwx">
    <div class="qwx-head">
      <span class="qwx-title">天气预警</span>
      <span class="qwx-time" :class="{ broken: broken.timeText }">
        {{ timeText }}
      </span>
    </div>

    <div class="qwx-hero">
      <i
          class="qi qwx-icon"
          :class="[`qi-${icon}`, { broken: broken.icon }]"
      ></i>
      <div class="qwx-hero-text">
        <div class="qwx-temp" :class="{ broken: broken.temp }">
          {{ temp }}<span class="qwx-unit">°C</span>
        </div>
        <div class="qwx-desc" :class="{ broken: broken.desc }">
          {{ desc }}
        </div>
      </div>
    </div>

    <div class="qwx-meta">
      <div class="qwx-meta-item">
        <div class="qwx-label">湿度</div>
        <div class="qwx-value" :class="{ broken: broken.humidity }">
          {{ humidity }}
        </div>
      </div>
      <div class="qwx-meta-item">
        <div class="qwx-label">风向</div>
        <div class="qwx-value" :class="{ broken: broken.wind }">
          {{ wind }}
        </div>
      </div>
      <div class="qwx-meta-item">
        <div class="qwx-label">空气质量</div>
        <div class="qwx-value" :class="{ broken: broken.aqi }">
          {{ aqi }}
        </div>
      </div>
    </div>

    <div class="qwx-foot">
      <span class="qwx-dot" :class="{ broken: broken.footer }"></span>
      <span class="qwx-foot-text" :class="{ broken: broken.footer }">
        {{ footer }}
      </span>
      <span class="qwx-src">和风天气</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

/* ---------- 正常值（一个健康的天气卡片应该长这样） ---------- */
const NORMAL = {
  temp: '29',
  desc: '雷阵雨',
  humidity: '78%',
  wind: '东南风 3级',
  aqi: '优',
  timeText: '2 分钟前更新',
  footer: '数据同步正常',
  icon: '1217',
}

const temp = ref(NORMAL.temp)
const desc = ref(NORMAL.desc)
const humidity = ref(NORMAL.humidity)
const wind = ref(NORMAL.wind)
const aqi = ref(NORMAL.aqi)
const timeText = ref(NORMAL.timeText)
const footer = ref(NORMAL.footer)
const icon = ref(NORMAL.icon)

const refs = { temp, desc, humidity, wind, aqi, timeText, footer, icon }

/* ---------- 每个字段“崩掉”时会短暂变成什么 ---------- */
const BROKEN = {
  temp:     ['NaN', '--', '∞', '29̸', 'Err', '?'],
  desc:     ['…', '[object]', '雷阵', '未定义', '雷阵?雨'],
  humidity: ['???', 'NaN', '998%', '-1%', '%'],
  wind:     ['北风 — 级', 'undefined', '?风 ?级', '——', 'N/A'],
  aqi:      ['ERR', '优?', 'NaN', '--', '良…'],
  timeText: ['-1 分钟前', '刚刚（旧）', '同步中…', '1970-01-01', '? 秒前'],
  footer:   ['数据同步异常', '连接已断开', '缓存 / 3 秒前', 'ERR_FETCH'],
  icon:     ['9999', '104', '900', '0'],
}

const broken = ref({
  temp: false, desc: false, humidity: false, wind: false,
  aqi: false, timeText: false, footer: false, icon: false,
})

/* ---------- 小工具 ---------- */
const timers = new Set()
function later(fn, ms) {
  const id = setTimeout(() => { timers.delete(id); fn() }, ms)
  timers.add(id)
  return id
}
const rand = (a, b) => a + Math.random() * (b - a)
const pick = arr => arr[Math.floor(Math.random() * arr.length)]

/* ---------- 单字段故障 ---------- */
function glitch(key) {
  if (broken.value[key]) return
  const r = refs[key]
  if (!r || !BROKEN[key]) return

  broken.value[key] = true
  r.value = pick(BROKEN[key])

  later(() => {
    r.value = NORMAL[key]
    broken.value[key] = false
  }, rand(80, 320))
}

/* ---------- 调度：不定期、单发或双发 ---------- */
function loop() {
  later(() => {
    const keys = Object.keys(refs)
    glitch(pick(keys))

    // 偶尔两个字段同时崩 —— 看起来才像“后端在批量吐脏数据”
    if (Math.random() < 0.35) {
      const others = keys.filter(k => !broken.value[k])
      if (others.length) glitch(pick(others))
    }
    loop()
  }, rand(500, 1900))
}

onMounted(loop)
onUnmounted(() => {
  timers.forEach(clearTimeout)
  timers.clear()
})
</script>

<style scoped>
/* =========================================================
   基础态：一个平平无奇的天气卡片，不要任何“设计感”
   ========================================================= */
.qwx {
  container-type: inline-size;
  font-family: system-ui, -apple-system, "PingFang SC", "Hiragino Sans GB",
  "Microsoft YaHei", sans-serif;
  color: #1f2329;
  font-size: 13px;
  line-height: 1.5;
  --c-main: #2f7cf6;
  --c-muted: #8a9099;
  --c-line: #eef0f3;
  --c-danger: #d3382f;
}

.qwx-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.qwx-title {
  font-weight: 600;
  font-size: 13px;
}
.qwx-time {
  font-size: 11px;
  color: var(--c-muted);
}

/* 主体：图标 + 温度 + 描述 */
.qwx-hero {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

.qwx-icon {
  font-family: 'qweather-icons', sans-serif;
  font-size: 38px;
  line-height: 1;
  color: var(--c-main);
  transition: color 0.15s, transform 0.15s;
}

.qwx-hero-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.qwx-temp {
  font-size: clamp(24px, 13cqw, 34px);
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.02em;
  color: #1f2329;
}
.qwx-unit {
  font-size: 0.55em;
  font-weight: 500;
  color: var(--c-muted);
  margin-left: 2px;
}

.qwx-desc {
  font-size: 12px;
  color: var(--c-muted);
}

/* 三栏数据 */
.qwx-meta {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--c-line);
}
.qwx-meta-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.qwx-label {
  font-size: 11px;
  color: var(--c-muted);
}
.qwx-value {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 底部状态条 */
.qwx-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 11px;
  color: var(--c-muted);
}
.qwx-dot {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  border-radius: 100%;
  background: #3fbf6f;
  transition: background 0.15s, box-shadow 0.15s;
}
.qwx-src {
  margin-left: auto;
  color: #c2c7cd;
}

/* =========================================================
   故障态：不夸张，就是“这一小块 momentarily 出了点问题”
   ========================================================= */
.qwx .broken {
  color: var(--c-danger);
  animation: microShift 90ms steps(1) infinite;
  /* 双色偏移，模拟亚像素错位 —— 现代浏览器里最像“渲染出问题”的观感 */
  text-shadow:
      -0.5px 0 0 rgba(255, 80, 80, 0.28),
      0.5px 0 0 rgba(80, 180, 255, 0.28);
}

@keyframes microShift {
  0%   { transform: translate3d(0, 0, 0); }
  30%  { transform: translate3d(-0.7px, 0, 0); }
  65%  { transform: translate3d(0.5px, 0, 0); }
  100% { transform: translate3d(0, 0, 0); }
}

/* 图标崩掉：掉色 + 一点点倾斜 */
.qwx .qwx-icon.broken {
  color: var(--c-danger);
  transform: translateX(0.5px) skewX(-2deg);
  filter: saturate(0.35);
}

/* 状态点崩掉：变红 + 光晕 */
.qwx .qwx-dot.broken {
  background: var(--c-danger);
  box-shadow: 0 0 6px rgba(211, 56, 47, 0.5);
}

/* 用户开了减弱动效就不抖了 */
@media (prefers-reduced-motion: reduce) {
  .qwx .broken { animation: none; }
}
</style>
