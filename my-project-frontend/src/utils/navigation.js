import {useRouter} from "vue-router";

/**
 * 返回上一页，没有上一页就回首页。
 * <p>
 * 不能直接用 {@code router.back()}：如果用户是**直接打开链接**进来的
 * （贴到地址栏、别人发给他、从收藏夹点进来），浏览历史里根本没有上一页，
 * back() 会什么都不做 —— 点了没反应，比没有按钮还糟。
 * <p>
 * Vue Router 会把"有没有上一页"记在 {@code history.state.back} 里，据此判断。
 * <p>
 * 抽出来是因为详情页和用户主页都要它 —— 两处各抄一遍的话，
 * 以后修 bug（比如发现 back 之后滚动位置不对要换写法）只会修一处。
 */
export function useGoBack(fallback = '/index/tieba') {
  const router = useRouter()
  return function goBack() {
    if (window.history.state?.back) {
      router.back()
    } else {
      router.push(fallback)
    }
  }
}
