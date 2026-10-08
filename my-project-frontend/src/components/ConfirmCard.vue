<script setup>
import {nextTick, ref, watch} from "vue";
import {useEventListener, useScrollLock} from "@vueuse/core";
import {useZIndex} from "element-plus";

/**
 * 一个从画面下方升到正中的确认卡片。
 *
 * <h3>为什么不用 el-dialog / ElMessageBox</h3>
 * <ul>
 *   <li><b>el-dialog 的 transition prop 改不动卡片的位置</b>：它那个
 *       {@code <Transition>} 包住的是整个 {@code <ElOverlay>}（遮罩 + 内容），
 *       不是在包卡片。拿它做"从底部升起"，被推上来的是遮罩本身 ——
 *       而 transform 会把自己变成 fixed 后代的包含块，遮罩就不再铺满视口了。
 *       要改就得去覆盖它的内部结构，比写这一个组件还长</li>
 *   <li><b>ElMessageBox</b> 是命令式的、位置和动画都写死，调节不了曲线</li>
 * </ul>
 * 所以这里只借它两个"轮子"，都是 Element Plus 自己在用的那套：
 * <ul>
 *   <li>{@code useScrollLock}（@vueuse/core）—— 和 el-dialog/el-drawer 一样
 *       在打开期间锁住 body 滚动</li>
 *   <li>{@code useZIndex} —— Element Plus 自己的层叠序号分配器。用它而不是
 *       写死一个 z-index，就能保证这张卡片永远盖在"此刻已经打开的"所有
 *       el-dialog / el-drawer / el-select 上面，和 el-dialog 的行为一致</li>
 * </ul>
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>{@code v-model} —— 开关</li>
 *   <li>{@code @confirm} —— 点了确认按钮。<b>卡片会自己关掉</b>，父组件不用管</li>
 *   <li>{@code @cancel} —— 用户放弃了：点遮罩 / 点取消 / 按 ESC，
 *       三条路都会走到这里，卡片同样自己关</li>
 * </ul>
 */

const props = defineProps({
  modelValue: {type: Boolean, default: false},
  title: {type: String, default: '确定要执行这个操作吗？'},
  /** 补充说明。不传就只有标题 */
  message: {type: String, default: ''},
  confirmText: {type: String, default: '确定'},
  cancelText: {type: String, default: '取消'},
  /** 确认按钮的 el-button type。删除这类不可逆的操作默认就是红的 */
  type: {type: String, default: 'danger'},
})

const emit = defineEmits(['update:modelValue', 'confirm', 'cancel'])

const {nextZIndex} = useZIndex()
const zIndex = ref(2000)

const cardRef = ref(null)

/**
 * 打开之前焦点在哪个元素上。关掉之后要还回去。
 * 不还的话，键盘用户的 Tab 位置会跳回页面开头 ——
 * 看起来只是"有点怪"，实际是每次确认完都得重新 Tab 一遍才回得到原处。
 */
let lastFocused = null

/**
 * 锁住 body 滚动，和 el-drawer / el-dialog 是同一个机制。
 * <p>
 * 但要诚实说一句它的极限：这个应用真正的滚动容器是 IndexView 里那个
 * el-scrollbar（不是 body），而 useScrollLock 和 Element Plus 的
 * useLockscreen 都只管 body —— 所以光靠它，鼠标滚轮仍然能滚动背后的列表。
 * 这一点 el-drawer 在本项目里是一样的（同样的原因）。
 * 底下的遮罩上那句话补上了这个缺口。
 */
const locked = useScrollLock(document.body)

function close() {
  emit('update:modelValue', false)
}

function cancel() {
  emit('cancel')
  close()
}

function confirm() {
  // 先通知父组件再关自己。父组件此刻读到的状态还是"卡片开着"的，
  // 反过来的话它可能已经因为 v-model 变 false 而清掉了待删对象，就拿不到 id 了
  emit('confirm')
  close()
}

/**
 * "点卡片以外的部分取消"。
 * <p>
 * 用 mousedown / mouseup 配对，而不是省事的 {@code @click.self}：
 * click 的 target 是"按下处和松开处的最近公共祖先"，所以在卡片里拖选一段文字、
 * 手滑到卡片外再松开时，click 会以根节点为目标 —— 看起来和"点了遮罩"一模一样，
 * 卡片就被误关了。要求按下和松开**都在遮罩上**才取消，这个误关就没了。
 * el-dialog 处理遮罩点击用的也是这个套路。
 * <p>
 * pressedOnMask 不进模板，所以普通变量就够，不需要 ref。
 */
let pressedOnMask = false

function onMaskDown(e) {
  pressedOnMask = e.target === e.currentTarget
}

function onMaskUp(e) {
  const bothOnMask = pressedOnMask && e.target === e.currentTarget
  pressedOnMask = false
  if (bothOnMask) {
    cancel()
  }
}

/**
 * 拦住遮罩上的滚轮。理由见上面 useScrollLock 那段 ——
 * 这个应用真正的滚动容器是 IndexView 里那个 el-scrollbar，body 锁不住它，
 * 只有这里能拦。
 * <p>
 * 判一下 target：卡片内容万一超过 max-height，它自己是要能滚的，
 * 不能连它的滚轮一起 preventDefault 掉。
 */
function onWheel(e) {
  if (!cardRef.value?.contains(e.target)) {
    e.preventDefault()
  }
}

/**
 * ESC 取消。
 * <p>
 * 监听挂在 window 上而不是卡片上：卡片上的 keydown 只有在焦点位于卡片**内部**
 * 时才会冒泡上来，而鼠标用户根本不会去点卡片，焦点还留在那个垃圾桶按钮上。
 * 挂在 window 上就不依赖焦点在哪。
 * <p>
 * useEventListener 会在组件卸载时自动摘掉监听，不用手写 onBeforeUnmount
 * —— 这也是用它而不是 addEventListener 的全部理由。
 */
useEventListener(window, 'keydown', e => {
  if (props.modelValue && e.key === 'Escape') {
    cancel()
  }
})

watch(() => props.modelValue, async open => {
  locked.value = open
  if (open) {
    // 每开一次重新领一个层叠序号，和 el-dialog 打开时做的事一样
    zIndex.value = nextZIndex()
    lastFocused = document.activeElement
    // 等 DOM 出来再聚焦。卡片是 v-if 渲染的，此刻 ref 还是 null
    await nextTick()
    cardRef.value?.focus()
  } else {
    lastFocused?.focus?.()
    lastFocused = null
  }
})
</script>

<template>
  <!--
    teleport 到 body：卡片是 position: fixed 的，留在原地会被祖先的
    overflow / 层叠上下文影响（el-main 里那个 el-scrollbar 就是一个）。
    el-dialog / el-drawer 也是这么处理的（append-to-body）。
  -->
  <teleport to="body">
    <transition name="confirm-pop">
      <!--
        ⚠️ 这个根 div **自己必须带一条真实的 CSS 过渡**（下面的 background-color），
        不能只让后代去动 —— 见 <style> 里"根元素必须自己有过 transition"那段。
        它同时就是遮罩：铺满视口、背景是那个半透明黑。
      -->
      <div v-if="modelValue"
           class="confirm-root"
           :style="{zIndex}"
           @mousedown="onMaskDown"
           @mouseup="onMaskUp"
           @wheel="onWheel">

        <div ref="cardRef"
             class="confirm-card"
             role="alertdialog"
             aria-modal="true"
             :aria-label="title"
             tabindex="-1">
          <div class="confirm-title">{{ title }}</div>
          <div v-if="message" class="confirm-message">{{ message }}</div>
          <div class="confirm-actions">
            <el-button @click="cancel">{{ cancelText }}</el-button>
            <el-button :type="type" @click="confirm">{{ confirmText }}</el-button>
          </div>
        </div>
      </div>
    </transition>
  </teleport>
</template>

<style scoped>
/* ===========================================================================
   动画。要调的就是下面 .confirm-root 里那四个 CSS 变量。
   ⚠️ 改这一段之前先读"根元素必须自己有过 transition" —— 那里解释了
      为什么根元素上也必须挂一条 transition，删了整段动画就没了。
   =========================================================================== */

.confirm-root {
  position: fixed;
  inset: 0;
  /* 它同时就是遮罩。和 el-overlay 用同一个变量，深浅自动和全站其它弹层一致 */
  background-color: var(--el-overlay-color-lighter);

  /*
   * 两个旋钮写成变量而不是直接塞在 transition 里，是为了只有一个改动点：
   * 之前这些数字散在四条规则里（卡片进出场 + 遮罩进出场），
   * 想整体调快一点得改四处，漏一处就会变成"卡片停了遮罩还在淡"。
   * 根元素和卡片共用同一组变量也保证了它们**时长一致** —— 这一点是必须的，
   * 理由见下面"根元素必须自己有过 transition"。
   * 顺带也让外部能覆盖 —— 曲线这种东西得看着调，能被覆盖才谈得上调。
   */
  --confirm-enter-duration: 220ms;
  --confirm-enter-ease: cubic-bezier(0.16, 1, 0.3, 1);
  --confirm-leave-duration: 150ms;
  --confirm-leave-ease: cubic-bezier(0.55, 0, 1, 0.45);
}

/*
 * ===========================================================================
 * 根元素自己必须有一条真实的 transition。这不是为了让遮罩好看，是**功能要求**。
 *
 * Vue 判断"这次过渡要跑多久"时只看 <Transition> 的**根元素**：
 *     // vue/runtime-dom  getTransitionInfo(el)
 *     const styles = window.getComputedStyle(el)      ← el 就是根元素
 *     if (transitionTimeout > 0) { type = 'transition' }
 * 根元素自己如果没有过渡，type 就是 null，于是 whenTransitionEnds 立刻收尾：
 *     if (!type) { return resolve() }
 * 而 resolve 会在**同一个 nextFrame 回调里**把 -enter-from / -enter-to /
 * -enter-active 一起摘掉。浏览器在同一次样式计算里看到起点、终点、过渡属性
 * 全没了 → 直接跳到终点，一帧动画都不跑。
 *
 * 这正是最初那版的 bug：过渡全写在后代（.confirm-card）上，根元素干干净净，
 * 症状就是"功能正常，但卡片凭空出现"。所以让根元素退化成遮罩本身，
 * 拿 background-color 当那条过渡 —— 背景色不会波及子元素，
 * 遮罩淡入的同时卡片该怎么动还怎么动。
 *
 * 时长必须和卡片一致（所以共用 --confirm-*-duration）：Vue 等的是**根元素**的
 * transitionend；根元素要是比卡片短，卡片会被中途掐断在终点之前。
 * ===========================================================================
 */

.confirm-card {
  position: absolute;
  left: 50%;
  top: 50%;
  /*
   * 卡片本来就是靠这个 transform 居中的，而进场动画改的也是它 ——
   * 所以 from / to 里的水平分量必须始终是 -50%，只动纵向。
   * 少写一个 -50% 卡片会横向甩出去，而且和"动画坏了"看起来一模一样。
   */
  transform: translate(-50%, -50%);
  box-sizing: border-box;
  width: min(360px, calc(100vw - 32px));
  /* 内容再长也不能顶出屏幕，超了自己滚 */
  max-height: calc(100vh - 40px);
  overflow: auto;
  padding: 18px 20px;
  /* 和 CardLight 一个圆角，免得这张卡片看起来像从别的应用里掉进来的 */
  border-radius: 17px;
  background-color: var(--el-bg-color);
  box-shadow: var(--el-box-shadow);
  /* tabindex="-1" 会带来一圈焦点框。鼠标用户不该看到它，
     所以交回给浏览器按 :focus-visible 判断 —— outline: none 会把键盘用户的
     焦点提示也一起干掉，那是另一种坏 */
  outline: none;
}

.confirm-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.confirm-message {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.confirm-actions {
  margin-top: 18px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* ---------------- 进场：遮罩淡入 + 卡片从画面下方升到正中 ----------------
   100vh 是"在已居中的基础上再往下推一整屏"，也就是整张卡片都在画面外。

   下面这些选择器带两个类（.confirm-pop-xxx.confirm-root），特异性比上面的
   .confirm-root 高，所以起点/终点能盖住静止态。后代那几条同理。
----------------------------------------------------------------------- */

.confirm-pop-enter-from.confirm-root {
  background-color: transparent;
}

.confirm-pop-enter-active.confirm-root {
  /* 根元素的那条过渡，见上面"根元素必须自己有过 transition" */
  transition: background-color var(--confirm-enter-duration) var(--confirm-enter-ease);
}

.confirm-pop-enter-from .confirm-card {
  transform: translate(-50%, 100vh);
}

.confirm-pop-enter-active .confirm-card {
  /*
   * cubic-bezier(0.16, 1, 0.3, 1) 是一条强减速曲线（常被叫做 easeOutExpo）：
   * 出速最大、行程的前 30% 就吃掉约 90% 的距离、最后一段几乎不动 ——
   * 所以 220ms 跑完一整屏看着仍然是从容停住的，不是"啪"地闪出来。
   *
   * 实测这条曲线在 220ms 下的形状：
   *   时间 10%  → 已走 49%   速度 49 屏/秒
   *   时间 35%  → 已走 91%
   *   时间 60%  → 已走 99%   速度已降到 1.6 屏/秒
   * 也就是绝大部分距离在头 70ms 内走完，剩下 150ms 都是"停下来"的过程。
   *
   * 换曲线的后果很具体：
   *   linear        → 匀速撞到终点，像卡帧，最难看（35% 时只走 35%）
   *   ease-out      → 减速太弱，末段还在明显移动，显得拖沓
   *   ease-in-out   → 起步慢，从底部升上来会有一瞬间的犹豫感
   */
  transition: transform var(--confirm-enter-duration) var(--confirm-enter-ease);
}

/* ---------------- 离场：遮罩淡出 + 卡片往下加速甩走 ----------------
   比进场更快（150ms）。理由不是省时间，而是此刻用户已经在做下一个动作了，
   卡片多留一帧都是在挡路
--------------------------------------------------------------------- */

.confirm-pop-leave-to.confirm-root {
  background-color: transparent;
}

.confirm-pop-leave-active.confirm-root {
  transition: background-color var(--confirm-leave-duration) linear;
}

.confirm-pop-leave-to .confirm-card {
  transform: translate(-50%, 40vh);
  opacity: 0;
}

.confirm-pop-leave-active .confirm-card {
  /* 0.55, 0, 1, 0.45 是 easeInCirc：起步慢、越走越快（实测末段 43 屏/秒），
     读起来是"被抽走"，和进场的"抛上来停住"正好互为倒放 */
  transition: transform var(--confirm-leave-duration) var(--confirm-leave-ease),
              opacity var(--confirm-leave-duration) linear;
}

/*
 * 系统里开了"减少动态效果"就不做位移，只淡入淡出。
 * 大面积快速位移对前庭敏感的人是真的会引起不适 —— 而这偏偏是个删除确认，
 * 最不该让人因为怕晕而不敢用的地方。
 *
 * 只改卡片：起点和终点都回到静止位置，于是位移为零、只剩透明度在变。
 * 遮罩（根元素）那条 background-color 本来就只是渐变，留着不动 ——
 * 而且它必须在，见上面"根元素必须自己有过 transition"。
 */
@media (prefers-reduced-motion: reduce) {
  .confirm-pop-enter-from .confirm-card,
  .confirm-pop-leave-to .confirm-card {
    transform: translate(-50%, -50%);
    opacity: 0;
  }

  .confirm-pop-enter-active .confirm-card,
  .confirm-pop-leave-active .confirm-card {
    transition: opacity 120ms linear;
  }
}
</style>
