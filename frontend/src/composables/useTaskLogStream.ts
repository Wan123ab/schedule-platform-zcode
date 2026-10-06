import { onUnmounted, ref, type Ref } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 步骤实时日志流（docs/07 §7.5 / PRD §13.2）。
 *
 * 【Vue 机制说明】
 * - 组合式函数（composable）：无界面的逻辑复用，返回响应式引用供组件绑定；
 * - 返回 `lines` 是 `Ref<LogLine[]>`：push 即触发模板更新 —— 日志窗口只需 v-for 渲染；
 * - onUnmounted 在 setup 上下文调用时自动清理连接（组件销毁 = 断开）。
 *
 * 【续传语义（offset）】
 * 每个 LOG 帧带 seq；断线重连时把"已收到的最大 seq"作为 offset 放回 URL，
 * 服务端从 DB 重放 seq > offset 的历史 —— 不丢、不重。这里是它在前端的一半。
 */
export interface LogLine {
  seq: number
  stream: string // EOF=stdout / ERR=stderr
  content: string
}

export function useTaskLogStream(taskId: Ref<string>, stepRowId: Ref<number | null>) {
  const lines = ref<LogLine[]>([])
  const connected = ref(false)
  const eof = ref(false)

  let socket: WebSocket | null = null
  let lastSeq = 0
  let retryTimer: number | null = null
  let retryDelay = 1000
  let closedByUser = false

  const auth = useAuthStore()

  function open() {
    if (!stepRowId.value || closedByUser) {
      return
    }
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    // token 走 query（I-07：企业代理会剥离升级请求的 Header）；offset = 已收最大 seq
    const url =
      `${proto}://${location.host}/ws/tasks/${taskId.value}/steps/${stepRowId.value}/logs` +
      `?offset=${lastSeq}&token=${encodeURIComponent(auth.token)}`
    socket = new WebSocket(url)

    socket.onopen = () => {
      connected.value = true
      retryDelay = 1000 // 重置退避
    }
    socket.onmessage = (event) => {
      try {
        const frame = JSON.parse(event.data as string)
        if (frame.type === 'LOG') {
          // 防御重复帧（网络重放）：seq 单调过滤，与后端"不重不丢"口径双保险
          if (frame.seq > lastSeq) {
            lastSeq = frame.seq
            lines.value.push({ seq: frame.seq, stream: frame.stream, content: frame.content })
          }
        } else if (frame.type === 'EOF') {
          eof.value = true
        } else if (frame.type === 'ERROR') {
          // 鉴权失败等：不自动重试（重试也一样失败）
          close()
        }
      } catch {
        // 非 JSON 帧：忽略
      }
    }
    socket.onclose = () => {
      connected.value = false
      if (!closedByUser && !eof.value) {
        scheduleReconnect() // 断线重连：携带 lastSeq 续传
      }
    }
    socket.onerror = () => socket?.close()
  }

  function scheduleReconnect() {
    if (retryTimer !== null) {
      return
    }
    retryTimer = window.setTimeout(() => {
      retryTimer = null
      retryDelay = Math.min(retryDelay * 2, 15_000) // 指数退避，封顶 15s
      open()
    }, retryDelay)
  }

  function close() {
    closedByUser = true
    if (retryTimer !== null) {
      window.clearTimeout(retryTimer)
      retryTimer = null
    }
    socket?.close()
    socket = null
    connected.value = false
  }

  /** 切换步骤：清空缓冲、重置游标、重开连接（切步即换通道）。 */
  function switchTo(nextRowId: number) {
    close()
    closedByUser = false
    lines.value = []
    lastSeq = 0
    eof.value = false
    stepRowId.value = nextRowId
    open()
  }

  if (stepRowId) {
    open()
  }
  onUnmounted(close)

  return { lines, connected, eof, switchTo }
}
