/**
 * 输入法组合期间隐藏 Quill 的占位符。
 *
 * ---------------------------------------------------------------------------
 * 解决什么问题
 * ---------------------------------------------------------------------------
 * 清空的编辑器里会显示占位文字（比如"说点什么"）。用拼音输入法打字时，
 * 拼音还在**组合缓冲区**里没提交，而 Quill 只在 TEXT_CHANGE 事件里更新占位符：
 *
 *     this.emitter.on(EDITOR_CHANGE, t => {
 *       if (t === TEXT_CHANGE)
 *         this.root.classList.toggle('ql-blank', this.editor.isBlank());
 *     });
 *
 * 组合中的内容不算 text change，所以 isBlank() 仍然是 true，
 * 占位符照旧显示 —— 屏幕上就是"正在打拼音，但占位文字还压在那儿"，
 * 看起来像编辑器没接住输入。
 *
 * 这里监听组合事件，组合期间给编辑器加一个类，用 CSS 把占位符藏掉。
 *
 * ---------------------------------------------------------------------------
 * 为什么用指令（v-xxx）而不是改组件逻辑
 * ---------------------------------------------------------------------------
 * 这是纯 DOM 行为，不涉及任何业务状态 —— 不需要 ref、不需要响应式，
 * 组件里也不该为了它多出几个变量。指令可以直接挂在 Quill 的 DOM 上，
 * 而且**能自动清理**（unmounted 时解绑），比在组件里手写 addEventListener
 * 再记得 removeEventListener 更不容易漏。
 *
 * ---------------------------------------------------------------------------
 * 用法
 * ---------------------------------------------------------------------------
 *     import {vHidePlaceholderWhileComposing} from '@/utils/hidePlaceholder'
 *
 *     <div class="editor-box" v-hide-placeholder-while-composing>
 *       <QuillEditor ... />
 *     </div>
 *
 * 挂在 Quill 的**外层容器**上 —— 组合事件会冒泡上来，
 * 这样不用去拿 Quill 内部的 DOM（那个拿不到稳定引用）。
 */

const COMPOSING_CLASS = 'is-composing'

function attach(el) {
    // 注意：不能直接改 .ql-editor 的样式，因为我们拿不到那个节点的稳定引用；
    // 在外层加类，用 CSS 后代选择器去命中，见下面 style 里注入的那条规则。
    const onStart = () => el.classList.add(COMPOSING_CLASS)
    const onEnd = () => el.classList.remove(COMPOSING_CLASS)

    // compositionstart / compositionend 在容器上监听即可（会冒泡）
    el.addEventListener('compositionstart', onStart, true)
    el.addEventListener('compositionend', onEnd, true)

    // compositionend 之后 Quill 才会收到 TEXT_CHANGE 并自己更新一次占位符，
    // 所以这里不用手动补刀。留一个防抖保险：万一 compositionend 没触发
    // （某些输入法切换焦点时会漏），blur 时清掉，避免占位符永久消失。
    const onBlur = () => el.classList.remove(COMPOSING_CLASS)
    el.addEventListener('blur', onBlur, true)

    // 把解绑函数挂在元素上，指令的 unmounted 钩子取用
    el.__hidePlaceholderCleanup = () => {
        el.removeEventListener('compositionstart', onStart, true)
        el.removeEventListener('compositionend', onEnd, true)
        el.removeEventListener('blur', onBlur, true)
        delete el.__hidePlaceholderCleanup
    }
}

export const vHidePlaceholderWhileComposing = {
    mounted(el) {
        // 样式注入一次就够。用 id 判断，避免多个编辑器挂同一份。
        // 为什么在这里注入而不是写在组件的 <style> 里：
        //   这条规则改的是 Quill 内部节点（.ql-editor:before 就是它的占位符实现），
        //   放组件样式里需要 :deep() 穿透，而且组件卸载后规则还在。
        //   放指令里，谁用谁生效，职责清楚。
        if (!document.getElementById('hide-placeholder-style')) {
            const style = document.createElement('style')
            style.id = 'hide-placeholder-style'
            style.textContent =
                '.is-composing .ql-editor.ql-blank::before { content: none !important; }'
            document.head.appendChild(style)
        }
        attach(el)
    },
    unmounted(el) {
        el.__hidePlaceholderCleanup?.()
    }
}
