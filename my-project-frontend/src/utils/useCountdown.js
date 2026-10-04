import {computed, onUnmounted, ref} from 'vue'

/**
 * 「获取验证码」按钮的读秒倒计时。
 *
 * ---------------------------------------------------------------------------
 * 为什么要有这个文件：原来注册页和重置密码页各写了一份，两份**都有 bug**
 * ---------------------------------------------------------------------------
 * 原来的写法是：
 *
 *     coldTime.value = 60;
 *     get(url, () => {
 *       setInterval(() => coldTime.value--, 1000)   // ← 问题就在这一行
 *     })
 *
 * 三个问题：
 *
 *   1. **计时器永远不停**。它会一直减到 0、-1、-2……
 *      而注册页按钮的条件是 `:disabled="!isEmailValid || coldTime"` ——
 *      负数在 JS 里是 truthy，于是 `-1` 让按钮**永久禁用**，读秒完再也点不动。
 *      （重置页写的是 `coldTime > 0`，所以那边症状不同，但根因相同。）
 *
 *   2. **重复点击会叠加计时器**。第一次点创建 interval#1，第二次又创建 #2，
 *      两个同时在减，秒数掉得比 1 秒 1 个快。
 *
 *   3. **组件卸载不清理**。用户读秒中途离开页面，那个 interval 还在跑，
 *      会去改一个已经销毁的组件的状态。
 *
 * 把这段抽成组合式函数，是为了让两个页面**共用同一份正确实现** ——
 * 各修一份的话，下次改还得记得改两处。
 *
 * ---------------------------------------------------------------------------
 * 用法
 * ---------------------------------------------------------------------------
 *     const { coldTime, isCounting, start } = useCountdown(60)
 *
 *     // 后端确认发送成功后调用
 *     start()
 *
 * 模板里：
 *     :disabled="!isEmailValid || isCounting"
 *     {{ isCounting ? `请等待${coldTime}秒` : '获取验证码' }}
 *
 * 注意用的是 `isCounting` 而不是直接判断 `coldTime` —— 后者容易写出
 * "负数也当禁用"这种 bug。把"是否在倒计时"这件事做成一个明确的布尔值，
 * 读代码和写代码时都不容易错。
 *
 * @param {number} seconds 倒计时秒数
 */
export function useCountdown(seconds = 60) {
    const coldTime = ref(0)
    let timer = null

    /** 停掉计时器。把 timer 置空是必要的，否则 stop 之后 isCounting 还会被骗。 */
    function stop() {
        if (timer !== null) {
            clearInterval(timer)
            timer = null
        }
    }

    /**
     * 开始倒计时。会先停掉已有的 —— 这样重复调用不会叠加计时器，
     * 调用方不需要自己保证"只调一次"。
     */
    function start() {
        stop()
        coldTime.value = seconds
        timer = setInterval(() => {
            coldTime.value--
            // 到 0 就停，并显式归零。
            // 只 clearInterval 而不归零的话，万一某次逻辑让它减过头，
            // 界面上会显示出 "-1 秒"这种数字。
            if (coldTime.value <= 0) {
                coldTime.value = 0
                stop()
            }
        }, 1000)
    }

    // 卸载时清理。放在组合式函数内部，用的人不用记得写。
    onUnmounted(stop)

    return { coldTime, start, stop, isCounting: computed(() => coldTime.value > 0)}
}
