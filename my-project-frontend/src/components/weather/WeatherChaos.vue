<!-- src/components/Weather.vue -->
<template>
  <div
      class="crisis"
      :class="{ 'is-flicker': flicker }"
      :style="{ '--chaos': chaos }"
  >
    <div class="sweep" aria-hidden="true" />
    <div class="scanlines" aria-hidden="true" />
    <div class="vignette" aria-hidden="true" />

    <div class="content">
      <header class="topline">
        <span>// CH-WARN</span>
        <span class="dot" />
        <span>UNSTABLE</span>
      </header>

      <div class="icons" aria-hidden="true">
        <!-- 雷电 -->
        <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="1.6" stroke-linejoin="round" stroke-linecap="round">
          <path d="M13 2 3.5 14H12l-1 8 9.5-12H12l1-8Z" />
        </svg>
        <!-- 风 -->
        <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="1.6" stroke-linecap="round">
          <path d="M3 8h11.5a3 3 0 1 0-3-3" />
          <path d="M3 12h15.5a3 3 0 1 1-3 3" />
          <path d="M3 16h7" />
        </svg>
        <!-- 洪涝 -->
        <svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="1.6" stroke-linecap="round">
          <path d="M2 14.5c2.5 0 2.5-1.8 5-1.8s2.5 1.8 5 1.8 2.5-1.8 5-1.8 2.5 1.8 5 1.8" />
          <path d="M2 19c2.5 0 2.5-1.8 5-1.8s2.5 1.8 5 1.8 2.5-1.8 5-1.8 2.5 1.8 5 1.8" />
        </svg>
      </div>

      <h1 class="glitch" :data-text="TEXT">{{ TEXT }}</h1>

      <p class="sub">CLIMATE DISASTER WARNING</p>

      <footer class="botline">
        <span>SRC 3/5</span>
        <span class="bar"><i :style="{ width: barWidth }" /></span>
        <span>{{ (chaos * 100).toFixed(0) }}%</span>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'

const TEXT = '气候灾害预警'

const chaos = ref(0.35)     // 0~1，驱动全局“过载程度”
const flicker = ref(false)  // 瞬时黑屏抖动

const barWidth = computed(() => `${(chaos.value * 100).toFixed(0)}%`)

const timers = new Set()
function later(fn, ms) {
  const id = setTimeout(() => {
    timers.delete(id)
    fn()
  }, ms)
  timers.add(id)
  return id
}

const rand = (a, b) => a + Math.random() * (b - a)

function loop() {
  later(() => {
    chaos.value = rand(0.2, 0.95)
    if (Math.random() < 0.14) {
      flicker.value = true
      later(() => (flicker.value = false), rand(50, 130))
    }
    loop()
  }, rand(140, 900))
}

onMounted(loop)
onUnmounted(() => {
  timers.forEach(clearTimeout)
  timers.clear()
})
</script>

<style scoped>
.crisis {
  --chaos: 0.4;
  position: relative;
  isolation: isolate;
  container-type: inline-size; /* 让内部用 cqw 跟着卡片宽度缩放 */
  width: 100%;
  border-radius: 16px;
  overflow: hidden;
  background:
      radial-gradient(120% 100% at 50% -10%, #12222e 0%, #070c11 55%, #020406 100%);
  color: #bfe6f7;
  font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace;
  display: grid;
  place-items: center;
  user-select: none;
  border: 1px solid rgba(70, 170, 210, 0.16);
  box-shadow:
      inset 0 0 46px rgba(10, 70, 100, 0.55),
      0 2px 10px rgba(0, 0, 0, 0.18);
}

/* ---------------- 内容层 ---------------- */

.content {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 13px 12px 12px;
  text-align: center;
}

.topline,
.botline {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  font-size: 9px;
  letter-spacing: 0.14em;
  color: rgba(150, 215, 240, 0.5);
}
.topline { justify-content: space-between; }
.botline { justify-content: space-between; }

.dot {
  width: 5px;
  height: 5px;
  margin-right: auto;
  border-radius: 100%;
  background: #ff4d6d;
  box-shadow: 0 0 8px #ff4d6d;
  animation: dotPulse 0.9s steps(2, end) infinite;
}
@keyframes dotPulse {
  0%, 49%   { opacity: 1; }
  50%, 100% { opacity: 0.15; }
}

.bar {
  flex: 1;
  height: 3px;
  background: rgba(120, 200, 230, 0.15);
  overflow: hidden;
}
.bar > i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #28d5ff, #ff4d6d);
  transition: width 0.22s steps(3, end);
}

/* ---------------- 天气图标 ---------------- */

.icons {
  display: flex;
  gap: 20px;
  color: #6fd6f5;
  filter: drop-shadow(0 0 8px rgba(80, 200, 255, 0.45));
}

.ico {
  width: 20px;
  height: 20px;
  opacity: 0.6;
  animation: icoPulse 2.4s ease-in-out infinite;
}
.ico:nth-child(2) { animation-delay: 0.3s; }
.ico:nth-child(3) { animation-delay: 0.6s; }

@keyframes icoPulse {
  0%, 100% { opacity: 0.45; }
  50%      { opacity: 1; }
}

/* ---------------- 故障主标题 ---------------- */

.glitch {
  position: relative;
  margin: 0;
  font-size: clamp(20px, 12.5cqw, 34px);
  font-weight: 800;
  letter-spacing: 0.06em;
  line-height: 1.05;
  color: #eaf8ff;
  white-space: nowrap;
  text-shadow:
      0 0 10px rgba(130, 225, 255, 0.35),
      0 0 36px rgba(60, 150, 220, 0.22);
  animation: textJitter 5.2s steps(1, end) infinite;
}

.glitch::before,
.glitch::after {
  content: attr(data-text);
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.glitch::before {
  color: #17e6ff;
  animation: glitchA 3.1s steps(1, end) infinite;
}

.glitch::after {
  color: #ff2f7a;
  animation: glitchB 2.4s steps(1, end) infinite;
}

/* 两层切片位移周期互质 → 不同步，看起来才“乱” */

@keyframes glitchA {
  0%, 100% { clip-path: inset(0 0 100% 0); transform: translate3d(0, 0, 0); }
  5%   { clip-path: inset(10% 0 74% 0); transform: translate3d(-0.09em, 0, 0); }
  7%   { clip-path: inset(10% 0 74% 0); transform: translate3d( 0.07em, 0, 0); }
  9%   { clip-path: inset(0 0 100% 0); }
  33%  { clip-path: inset(0 0 100% 0); }
  35%  { clip-path: inset(46% 0 40% 0); transform: translate3d( 0.11em, 0, 0); }
  37%  { clip-path: inset(0 0 100% 0); }
  62%  { clip-path: inset(0 0 100% 0); }
  63%  { clip-path: inset(72% 0 12% 0); transform: translate3d(-0.13em, 0, 0); }
  65%  { clip-path: inset(0 0 100% 0); }
}

@keyframes glitchB {
  0%, 100% { clip-path: inset(0 0 100% 0); transform: translate3d(0, 0, 0); }
  18%  { clip-path: inset(0 0 100% 0); }
  19%  { clip-path: inset(28% 0 58% 0); transform: translate3d( 0.12em, 0, 0); }
  21%  { clip-path: inset(0 0 100% 0); }
  54%  { clip-path: inset(0 0 100% 0); }
  55%  { clip-path: inset(6% 0 84% 0); transform: translate3d(-0.10em, 0, 0); }
  57%  { clip-path: inset(0 0 100% 0); }
  81%  { clip-path: inset(60% 0 26% 0); transform: translate3d( 0.08em, 0, 0); }
  83%  { clip-path: inset(0 0 100% 0); }
}

@keyframes textJitter {
  0%, 100% { transform: translate3d(0, 0, 0); }
  16.5%    { transform: translate3d(0.02em, 0, 0); }
  17%      { transform: translate3d(-0.018em, 0.012em, 0); }
  17.5%    { transform: translate3d(0, 0, 0); }
  68%      { transform: translate3d(0, 0, 0); }
  68.4%    { transform: translate3d(-0.025em, 0, 0); }
  68.8%    { transform: translate3d(0, 0, 0); }
}

.sub {
  margin: 0;
  font-size: 8px;
  letter-spacing: 0.25em;
  color: rgba(140, 215, 245, 0.62);
  white-space: nowrap;
}

/* ---------------- 全局过载特效 ---------------- */

.scanlines {
  position: absolute;
  inset: -4px;
  z-index: 3;
  pointer-events: none;
  background: repeating-linear-gradient(
      to bottom,
      rgba(0, 0, 0, 0) 0 2px,
      rgba(0, 0, 0, 0.38) 2px 3px
  );
  opacity: calc(0.3 + var(--chaos) * 0.5);
  animation: scanRoll 0.6s steps(3, end) infinite;
  will-change: transform;
}
@keyframes scanRoll {
  from { transform: translateY(0); }
  to   { transform: translateY(3px); }
}

.sweep {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 26%;
  z-index: 4;
  pointer-events: none;
  background: linear-gradient(
      to bottom,
      transparent 0%,
      rgba(120, 225, 255, 0.05) 45%,
      rgba(120, 225, 255, 0.11) 50%,
      rgba(120, 225, 255, 0.05) 55%,
      transparent 100%
  );
  will-change: transform;
  animation: sweepMove 6.5s cubic-bezier(0.5, 0, 0.5, 1) infinite;
}
@keyframes sweepMove {
  0%   { transform: translateY(-120%); }
  100% { transform: translateY(520%); }
}

.vignette {
  position: absolute;
  inset: 0;
  z-index: 5;
  pointer-events: none;
  background: radial-gradient(
      100% 100% at 50% 50%,
      transparent 42%,
      rgba(0, 0, 0, 0.78) 100%
  );
}

/* 瞬时强抖动：只作用在内容层，不影响卡片布局 */
.is-flicker .content {
  animation: hardFlicker 0.13s steps(2, end) 1;
}
@keyframes hardFlicker {
  0%   { transform: translate3d(-4px, 0, 0); filter: hue-rotate(14deg) brightness(1.3); }
  50%  { transform: translate3d( 3px, -1px, 0); }
  100% { transform: translate3d(0, 0, 0); }
}

/* 尊重系统减弱动效设置 */
@media (prefers-reduced-motion: reduce) {
  .crisis *,
  .crisis *::before,
  .crisis *::after {
    animation: none !important;
  }
}
</style>
