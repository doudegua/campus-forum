/**
 * 时间格式化。
 * <p>
 * 抽成公共函数是因为列表卡片和详情页都要用 —— 各写一遍的话，
 * 迟早会变成"列表显示 09-24 07:02、详情显示 2026-09-24 07:02"，
 * 而这种不一致没人会当成 bug 报上来。
 */

function pad(n) {
  return String(n).padStart(2, '0')
}

/** 解析后端给的时间。解析不出来就返回 null，让调用方决定显示什么 */
function parse(value) {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** 09-24 07:02 —— 列表里一行要塞类型和作者，省这点宽度 */
export function formatTime(value) {
  const date = parse(value)
  if (!date) return ''
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
      `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 2026-09-24 07:02 —— 详情页只有一个时间，不用省 */
export function formatFullTime(value) {
  const date = parse(value)
  if (!date) return ''
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
      `${pad(date.getHours())}:${pad(date.getMinutes())}`
}
