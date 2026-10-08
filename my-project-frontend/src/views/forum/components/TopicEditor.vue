<script setup>
import {computed, onBeforeUnmount, onMounted, ref, watch} from "vue";
import {EditPen} from "@element-plus/icons-vue";
import {QuillEditor} from "@vueup/vue-quill";
import "@vueup/vue-quill/dist/vue-quill.snow.css";
import axios from "axios";
import {ElMessage} from "element-plus";
import {accessHeader} from "@/net";
import {compressImage} from "@/utils/image";
import {vHidePlaceholderWhileComposing} from "@/utils/hidePlaceholder";

const props = defineProps({
  modelValue: {type: Boolean, default: false},
  /**
   * 要编辑的帖子。null = 发帖（默认），非 null = 编辑这一条。
   * <p>
   * <b>这一个 prop 同时决定三件事</b>：标题和按钮文案、提交时打到哪个接口、
   * 以及**要不要碰草稿**（见下面 saveDraft 那段，那是本组件最要紧的一条隔离）。
   * <p>
   * 需要 {@code type}（类型 id，不是 typeName）、{@code title}、{@code content}
   * 三个字段 —— 正好是详情接口返回的形状。
   */
  topic: {type: Object, default: null}
});

const emit = defineEmits(['update:modelValue', 'submit']);

const content = ref('');
const topicType = ref('');
const title = ref('');

/**
 * 帖子类型从后端拿（GET /api/forum/topic_type）。
 * 原来这里是硬编码的四个字符串 —— 那样后端加了类型前端也不知道。
 * 注意 value 用的是 id 而不是名字：帖子以后要存的是类型 id，改名字不该影响已有帖子。
 */
const topicTypes = ref([]);
const typesLoading = ref(false);
/**
 * 类型加载失败的原因。
 * 非空的时候不能再只说"请先选择主题类型" —— 那是句谎话：下拉框里根本没东西可选，
 * 用户会一直以为是自己没选。
 */
const typesError = ref('');

const plainContent = computed(() => content.value
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;/g, ' ')
    .trim());
const count = computed(() => plainContent.value.length);

/**
 * 帖子里到底有没有实质内容：有文字，或者至少有一张图。
 * <p>
 * 不能用 content.value.trim() 判断空 —— Quill 的空文档是 <p><br></p>，不是空串，
 * 那样判断的话"空草稿"永远被当成有内容，发帖后草稿删不掉。
 */
const hasContent = computed(() => Boolean(plainContent.value) || content.value.includes('<img'))

// 抽屉开关。用 computed 双向代理，父组件 v-model 就能直接控制
const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value)
});

/** 是不是在编辑已有的帖子。发帖和编辑共用这一个抽屉，全靠它区分 */
const isEdit = computed(() => props.topic != null)

/**
 * 把要编辑的帖子填进表单。父组件是"先给 topic，再打开"，所以这里不能写在
 * onMounted 里 —— 那时抽屉还没开过，填进去也没人看得到。
 * <p>
 * 交给 watch(modelValue) 在**每次打开时**填：抽屉是可以反复开关的，
 * 第二次打开的是另一条帖子，onMounted 早就跑完了。
 * <p>
 * 关于 content 的时序：赋值时 Quill 实例可能还不存在（抽屉第一次打开时
 * el-drawer 才渲染内容），那时 @vueup/vue-quill 的 content watcher 会直接
 * return。但这不会丢 —— Quill 初始化完自己会 setContents(props.content)，
 * 用的正是这一刻赋的值。两条路都覆盖到了，所以同步赋值就够，不用等 nextTick。
 */
function applyTopic(t) {
  topicType.value = t.type ?? ''
  title.value = t.title ?? ''
  content.value = t.content ?? ''
}

watch(() => props.modelValue, open => {
  if (open && isEdit.value) {
    applyTopic(props.topic)
  }
})

/* ---------------- 编辑器与图片上传 ---------------- */

/**
 * 允许的图片类型。这个数组同时用在两处：Quill 的过滤名单，和给用户的错误提示。
 * 必须共用同一份 —— 否则提示里说的和实际拦的会慢慢对不上，
 * 而"提示和事实矛盾"是最让人没法自助排查的一类问题。
 */
const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']

/**
 * 注意 toolbar 也必须放在 options 里，不能用 QuillEditor 的 toolbar prop。
 * @vueup/vue-quill 内部是 Object.assign({}, props.options, clientOptions)，
 * 而 clientOptions.modules 是由 toolbar prop 生成的、并且排在最后 ——
 * 两个同时传的话，它会把我这里的 modules（含 uploader）整个覆盖掉，
 * 表现是图片按钮点了没反应，而且不报任何错。
 */
const editorOptions = {
  modules: {
    toolbar: [
      [{header: [1, 2, 3, 4, 5, 6, false]}],
      ['bold', 'italic', 'underline'],
      [{list: 'ordered'}, {list: 'bullet'}, {align: []}],
      ['blockquote', 'code-block', 'link', 'image'],
      [{color: []}, 'clean'],
    ],
    uploader: {
      mimetypes: ALLOWED_TYPES,
      // 必须是普通方法，不能写成箭头函数：Quill 是用 call 把 this 绑成
      // uploader 模块再调用的，我们要靠 this.quill 拿到编辑器实例。
      handler(range, files) {
        uploadImages(this.quill, range, Array.from(files))
      },
    },
  },
}

/**
 * Quill 实例。必须自己存一份：@vueup/vue-quill 对空字符串是"不处理"的，
 * 想清空编辑器只能绕开它直接调 Quill 的 API（见 clearEditor）。
 */
let quillRef = null

/**
 * 编辑器就绪。顺便把 uploader 包一层，用于报告被丢弃的文件。
 */
function onEditorReady(quill) {
  quillRef = quill
  reportRejectedFiles(quill)
}

/**
 * Quill 的 uploader.upload 会把不在 mimetypes 名单里的文件直接丢掉：
 * 不报错、不提示、什么都不发生。
 * <p>
 * 文件选择框那边有 accept 属性挡着，用户挑不到错的；但**拖拽不受 accept 限制**，
 * 拖一个 .txt 或一张 HEIC 进来会毫无反应，看起来像编辑器坏了。
 * 这里包一层 upload()，在它丢掉文件之前把这些文件报给用户 ——
 * 这是唯一还能看到它们的地方（handler 根本不会被调用）。
 */
function reportRejectedFiles(quill) {
  const uploader = quill.uploader
  if (!uploader) {
    return
  }
  const originalUpload = uploader.upload.bind(uploader)
  uploader.upload = (range, files) => {
    const rejected = Array.from(files).filter(file => !ALLOWED_TYPES.includes(file.type))
    if (rejected.length > 0) {
      const names = rejected.map(file => file.name || file.type || '未知文件').join('、')
      ElMessage.warning(`不支持的图片格式：${names}（只支持 JPG / PNG / GIF / WebP）`)
    }
    originalUpload(range, files)
  }
}

/**
 * 清空编辑器。
 * <p>
 * 不能只把 content 设成空串：@vueup/vue-quill 监听 content 的回调里有一句
 * `if (!quill || !newContent || ...) return;`，空串是 falsy，它直接返回，
 * 编辑器里的内容原封不动。结果是发帖之后界面看着还有旧内容，
 * 而编辑器一有动静就把旧内容重新 emit 回来，草稿又被存回去。
 * 所以这里绕过它，直接调 Quill 自己的 API。
 * 用 silent 是为了不让它 emit，避免和上面那句判断来回打架。
 */
function clearEditor() {
  if (quillRef) {
    quillRef.setText('', 'silent')
  }
  content.value = ''
}

/** 留出余量：后端自己的上限是 900KB，比它小才轮不到容器那层报错 */
const MAX_UPLOAD_BYTES = 900 * 1024

/**
 * 插入位置不用自己算，Quill 已经算好了：
 * 拖拽时它用 caretRangeFromPoint(鼠标坐标) 求出落点，点工具栏按钮时用当前光标位置，
 * 两种情况都通过 range 传进来，直接用即可。
 */
async function uploadImages(quill, range, files) {
  // range.index 在上传期间可能因用户继续打字而失效。这里沿用 Quill 自带 handler
  // 的做法：用捕获到的那一刻的 index，而不是插入时重新取当前光标
  let index = range ? range.index : Math.max(0, quill.getLength() - 1)
  let inserted = 0

  for (const file of files) {
    try {
      const compressed = await compressImage(file)
      if (compressed.size > MAX_UPLOAD_BYTES) {
        ElMessage.warning(`${file.name} 压缩后仍有 ${Math.round(compressed.size / 1024)}KB，请换一张`)
        continue
      }
      const key = await uploadImage(compressed)
      // 存相对路径：帖子 HTML 是在前端源上渲染的，靠 vite 的 /api 代理转给后端。
      // 这样数据库里的内容就不跟部署环境绑定了
      quill.insertEmbed(index + inserted, 'image', `/api/image/${key}`, 'user')
      inserted++
    } catch (e) {
      ElMessage.error(`${file.name} 上传失败：${e.message || e}`)
    }
  }
  if (inserted > 0) {
    quill.setSelection(index + inserted, 0, 'silent')
  }
}

/**
 * 直接用 axios，而不是 @/net 里的 post。
 * 原因：post 的失败回调只在「响应体 code !== 200」时触发，网络层出错时它只调自己的
 * 默认处理器，我这边永远收不到通知 —— 上传失败时这段代码会一直挂着，
 * 界面卡在"上传中"且没有任何提示。
 */
async function uploadImage(file) {
  const formData = new FormData()
  formData.append('file', file)
  const {data} = await axios.post('/api/image/upload', formData, {headers: accessHeader()})
  if (data.code !== 200) {
    throw new Error(data.message)
  }
  return data.data
}

/* ---------------- 草稿自动保存 ----------------
   本地是唯一真相，云端将来只是复制品。原因：localStorage 写入是同步的、跟网络无关，
   不会因为 401 / 断网 / 服务端 5xx 而失败。云端同步要靠"服务端确认后才清本地"来兜底，
   绝不能反过来把本地当临时缓存。

   存成 {content, updatedAt} 而不是裸字符串，是为了第二步接后端时能直接做版本比较，
   不用再迁一次数据格式。updatedAt 现在没人读，但那时候要用。

   之所以不做"关闭前弹窗确认"：那是在拦用户，自动保存是在解决问题本身。
------------------------------------------------ */
const DRAFT_KEY = 'tieba:topic:draft';
const SAVE_DELAY = 500;

let saveTimer = null;

/* ---------------- 草稿的读写必须在发帖/编辑之间**完全隔离** ----------------

   下面三个函数都带着同一句 `if (isEdit.value) return;`，这不是重复，是**唯一的**保险：
   草稿的 key 只有一个（DRAFT_KEY），而它是给"新帖"用的。

   编辑模式下不隔离会发生两件真正丢数据的坏事：
     1. 打开编辑 → loadDraft() 把你存档的新帖草稿塞进编辑器，
        你看到的"帖子原文"其实是你没发完的草稿；
     2. 关掉编辑 → saveDraft() 把**正在编辑的帖子内容**写进那个 key，
        你的新帖草稿被静默覆盖，而且不可恢复。
   再加上 resetForm() 里那句 clearDraft()，保存一次编辑就把草稿整个删了。

   所以判据不是"要不要保存"，而是"**这份内容是哪个任务的**"。
   只要 isEdit 为真，草稿机制和这次编辑没有任何关系，一律不碰。
----------------------------------------------- */

function loadDraft() {
  if (isEdit.value) {
    return;
  }
  const raw = localStorage.getItem(DRAFT_KEY);
  if (!raw) {
    return;
  }
  try {
    content.value = JSON.parse(raw)?.content ?? '';
  } catch {
    // 兼容早期直接存裸字符串的格式
    content.value = raw;
  }
}

function saveDraft() {
  if (isEdit.value) {
    return;
  }
  // 用 hasContent 而不是 content.value.trim()：Quill 的空文档是 <p><br></p>，
  // 字符串非空但没有任何实质内容，用它判断的话空草稿会被一直存着
  if (!hasContent.value) {
    localStorage.removeItem(DRAFT_KEY);
    return;
  }
  localStorage.setItem(DRAFT_KEY, JSON.stringify({
    content: content.value,
    updatedAt: Date.now()
  }));
}

function clearDraft() {
  clearTimeout(saveTimer);
  if (isEdit.value) {
    return;
  }
  localStorage.removeItem(DRAFT_KEY);
}

watch(content, () => {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(saveDraft, SAVE_DELAY);
});

/**
 * 发送前的校验。返回给用户看的原因；返回 null 表示可以发。
 * <p>
 * 之所以不用「按钮置灰」来拦：三个必填项里少了哪一个，置灰的按钮说不清楚，
 * 用户只能自己猜是缺主题、缺标题还是缺正文。让他点，然后明确告诉他缺什么。
 */
/**
 * 前端的限制，刻意比后端紧一点（后端是 60 / 12000）。
 * 分工是：前端负责"友好地拦住"，后端负责"兜底"。
 * 两边卡同一个数的话，只要有一点点口径差异就会出现"前端放行、后端拒绝"，
 * 而用户看到的是依据不明的报错。
 */
const TITLE_MAX = 50
const CONTENT_TEXT_MAX = 10000

function validate() {
  // 这三种"选不了类型"的原因要分开说。混成一句"请先选择主题类型"的话，
  // 用户会以为是自己的操作问题，而其实他什么都做不了
  if (typesError.value) {
    return `帖子类型没加载出来，暂时发不了帖：${typesError.value}`
  }
  if (topicTypes.value.length === 0) {
    return '后端还没有配置帖子类型，暂时发不了帖'
  }
  if (!topicType.value) {
    return '请先选择主题类型'
  }
  if (!title.value.trim()) {
    return '请填写标题'
  }
  if (title.value.trim().length > TITLE_MAX) {
    return `标题不能超过 ${TITLE_MAX} 个字`
  }
  if (!plainContent.value) {
    // plainContent 是把 HTML 标签剥掉之后的纯文本，图片不贡献任何文字，
    // 所以"只插了图、没写字"在这里表现为空。单独给一句，
    // 免得用户以为图片没传上去
    return content.value.includes('<img')
        ? '图片已经传好了，但帖子还需要写点文字'
        : '请输入帖子内容'
  }
  if (plainContent.value.length > CONTENT_TEXT_MAX) {
    return `内容不能超过 ${CONTENT_TEXT_MAX} 个字`
  }
  return null
}

/**
 * 拉取帖子类型。直接走 axios 而不是 @/net 的 get：
 * get 在网络层出错时只调它自己的默认处理器，失败回调不会触发，
 * 我这边就分不清"请求失败了"和"后端返回了空列表"，而这两种情况要给的提示完全不同。
 */
async function loadTopicTypes() {
  typesLoading.value = true;
  typesError.value = '';
  try {
    const {data} = await axios.get('/api/forum/topic_type', {headers: accessHeader()});
    if (data.code !== 200) {
      throw new Error(data.message);
    }
    topicTypes.value = data.data ?? [];
  } catch (e) {
    typesError.value = e.message || '加载失败';
    console.warn('帖子类型加载失败：', e);
  } finally {
    typesLoading.value = false;
  }
}

/** 提交中。防止连点，也顺便把按钮变成加载态 */
const sending = ref(false)

function submit() {
  const reason = validate()
  if (reason) {
    ElMessage.warning(reason)
    return
  }
  if (sending.value) {
    return
  }
  sending.value = true
  // 刻意不在这里清空表单和草稿：等父组件告诉我们服务端确实写成功了再清。
  // 否则发帖失败（网络断了、被限流、后端校验没过）时，
  // 用户写的东西和草稿会一起消失，而且不可恢复。
  emit('submit', {
    // 发帖是 null，编辑是那条帖子的 id —— 父组件据此决定打到
    // POST /topic 还是 POST /topic/{id}。这里不自己发请求，
    // 因为"发完之后要刷新什么"是页面才知道的事（列表页要重拉列表、
    // 详情页要重拉详情），抽屉不该替它们做决定
    id: isEdit.value ? props.topic.id : null,
    topic: {
      type: topicType.value,
      title: title.value.trim(),
      content: content.value
    },
    done: (ok) => {
      sending.value = false
      if (ok) {
        resetForm()
      }
    }
  })
}

function resetForm() {
  topicType.value = '';
  title.value = '';
  // 必须走 clearEditor 而不是直接 content.value = ''：
  // 后者不会真的清空编辑器，见 clearEditor 上面的说明
  clearEditor();
  // 编辑模式下 clearDraft 内部会自己 return（草稿不属于这次编辑）
  clearDraft();
  visible.value = false;
}

onMounted(() => {
  loadDraft();
  loadTopicTypes();
});

// 现在点外面、按 ESC 都能关，用户很可能敲完字立刻关掉就走人。
// 防抖还没到点就卸载组件的话，草稿会丢，所以卸载前把待存的冲一次。
onBeforeUnmount(() => {
  clearTimeout(saveTimer);
  saveDraft();
});
</script>

<template>
  <!-- 这层 div 不能省：scoped CSS 的 :deep(.el-drawer) 会编译成 [data-v-x] .el-drawer，
       需要一个带 scope id 的祖先元素。drawer 本身当根节点就匹配不到了。 -->
  <div>
    <!-- close-on-click-modal / close-on-press-escape 保持默认的 true：
         有草稿自动保存兜底，误关不会丢内容，就没必要再限制用户怎么关。
         反过来卡住"只能点叉号"反而违背直觉。 -->
    <el-drawer v-model="visible"
               direction="btt"
               :size="650">
      <template #header>
        <div>
          <div style="font-weight: bold;">{{ isEdit ? '编辑帖子' : '发帖' }}</div>
          <!--
            副标题不只是文案差别，它是在告诉用户"关掉会怎样"：
            发帖有草稿兜底，编辑没有 —— 编辑改的是已有内容，
            要保留就该点保存。说清楚比让用户自己试出来强
          -->
          <div style="font-size: 14px;color: gray">
            {{ isEdit ? '修改后直接覆盖原内容，关掉则不保存' : '草稿会自动保存，误关不会丢' }}
          </div>
        </div>
      </template>

      <div class="topic-meta">
        <el-select v-model="topicType"
                   class="topic-type"
                   placeholder="选择主题类型……"
                   :show-arrow="false"
                   popper-class="light-card-popper"
                   :loading="typesLoading"
                   :disabled="typesLoading || topicTypes.length === 0">
          <el-option v-for="type in topicTypes"
                     :key="type.id"
                     :label="type.name"
                     :value="type.id"/>
        </el-select>
        <el-input v-model="title" class="topic-title" placeholder="请输入主题">
          <template #prefix>
            <el-icon><EditPen/></el-icon>
          </template>
        </el-input>
      </div>

      <!--
        v-hide-placeholder-while-composing 挂在编辑器容器上：
        输入法组合期间把 Quill 的占位符藏起来，否则打拼音时占位文字还压在上面。
        详见 utils/hidePlaceholder.js。
      -->
      <div class="editor-box" v-hide-placeholder-while-composing>
        <QuillEditor v-model:content="content"
                     content-type="html"
                     theme="snow"
                     :options="editorOptions"
                     placeholder="说点什么"
                     @ready="onEditorReady"/>
      </div>
      <!--
        字数统计放在编辑框**外面**（原来在框内左下角，是绝对定位压在内容上的）。
        挪出来之后它就在下边框下方左侧，不会和正文重叠，
        也不用再和 Quill 的 padding 较劲。

        格式 n/上限：只说"n 字"的话，用户不知道上限是多少，
        要等到超了被拒才知道。上限取自 CONTENT_TEXT_MAX，
        和 validate() 用的是同一个常量 —— 避免显示一个数、实际卡另一个数。
      -->
      <div class="counter">{{ count }}/{{ CONTENT_TEXT_MAX }} 字</div>

      <template #footer>
        <div class="footer">
          <!-- 不置灰：缺什么由点击后 validate() 明确告知，而不是让用户对着灰按钮猜 -->
          <el-button type="primary" :loading="sending" @click="submit">
            {{ isEdit ? '保存' : '发送' }}
          </el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
:deep(.el-drawer) {
  width: 800px;
  margin: auto;
  border-radius: 20px 20px 0 0;
}

.editor-box {
  position: relative;
}

.topic-meta {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}

.topic-type {
  width: 180px;
  flex-shrink: 0;
}

.topic-title {
  flex: 1;
}

.editor-box :deep(.ql-toolbar.ql-snow) {
  border-color: var(--el-border-color);
  border-radius: 15px 15px 0 0;
}

.editor-box :deep(.ql-toolbar button:hover),
.editor-box :deep(.ql-toolbar button:focus),
.editor-box :deep(.ql-toolbar button.ql-active),
.editor-box :deep(.ql-toolbar .ql-picker-label:hover),
.editor-box :deep(.ql-toolbar .ql-picker-label.ql-active),
.editor-box :deep(.ql-toolbar .ql-picker.ql-expanded .ql-picker-label) {
  border-radius: var(--el-border-radius-base);
  background-color: var(--el-fill-color);
}

.editor-box :deep(.ql-container.ql-snow) {
  height: 300px;
  border-color: var(--el-border-color);
  border-radius: 0 0 15px 15px;
}

.editor-box :deep(.ql-editor) {
  padding-bottom: 30px;
}

.editor-box :deep(.ql-editor.ql-blank::before) {
  color: var(--el-text-color-placeholder);
}

.editor-box :deep(.ql-snow .ql-stroke) {
  stroke: var(--el-text-color-primary);
}

.editor-box :deep(.ql-snow .ql-fill) {
  fill: var(--el-text-color-primary);
}

.editor-box :deep(.ql-snow .ql-picker) {
  color: var(--el-text-color-primary);
}

.editor-box :deep(.ql-snow .ql-picker:not(.ql-color-picker):not(.ql-icon-picker) .ql-picker-options) {
  border: none;
  border-radius: 17px;
  background-color: var(--el-bg-color);
  box-shadow: var(--el-box-shadow-light);
  padding: 6px;
  overflow: hidden;
}

.editor-box :deep(.ql-snow .ql-picker:not(.ql-color-picker):not(.ql-icon-picker) .ql-picker-item) {
  height: auto;
  border-radius: var(--el-border-radius-base);
  color: var(--el-text-color-primary);
  padding: 5px 8px;
}

.editor-box :deep(.ql-snow .ql-picker:not(.ql-color-picker):not(.ql-icon-picker) .ql-picker-item:hover),
.editor-box :deep(.ql-snow .ql-picker:not(.ql-color-picker):not(.ql-icon-picker) .ql-picker-item.ql-selected) {
  background-color: var(--el-fill-color);
}

/*
 * 字数统计在编辑框外面，所以要改成普通流式布局。
 * 原来它是 position:absolute 定位在 Quill 内部的左下角 ——
 * 那样会压在正文内容上（正文长了就重叠），而且要去和 Quill 的 padding 对齐。
 * 挪到框外之后不需要绝对定位了。
 *
 * 这一条**不能**加 scoped 的 :deep() —— counter 是本组件的元素，
 * 不在 Quill 内部，普通的 scoped 规则就能命中（和上面那些 :deep() 不同）。
 */
.counter {
  margin-top: 6px;
  padding-left: 2px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  text-align: left;
}

.footer {
  display: flex;
  justify-content: flex-end;
}
</style>
