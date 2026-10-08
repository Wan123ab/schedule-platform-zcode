import { onUnmounted, ref, type Ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { deepCamel, deepSnake } from '@/utils/casename'
import type {
  DryRunCmdFrame,
  DryRunEofFrame,
  DryRunErrorFrame,
  DryRunFrameType,
  DryRunLogFrame,
  DryRunParams,
} from '@/api/types/operator'
import type { LogLine } from '@/composables/useTaskLogStream'

/**
 * 算子试运行流（PRD §10.6；帧协议复用 docs/07 §7.5 的词汇表）。
 *
 * 【为什么不用 EventSource】
 * ① 后端这个端点是 **POST**（参数走请求体，不进访问日志/代理日志/浏览器历史 —— 敏感参数
 *    不该出现在 URL 里）；`EventSource` 只支持 GET。
 * ② 认证走 `Authorization: Bearer`（D-06），`EventSource` 加不了自定义头。
 * 所以用 `fetch` + `ReadableStream` 手工解析 SSE —— 代价是要自己处理分帧（见下）。
 *
 * 【与 useTaskLogStream 的分工】
 * 那个是**任务日志**：WebSocket、落库、要 offset 续传、多客户端订阅。
 * 这个是**试运行**：一次性、不落库、单客户端 —— 断了不必续传（重跑一次即可），
 * 所以这里**刻意不做重连**（重连会重复执行远端命令，比丢日志严重得多）。
 *
 * 【两条错误通道（很重要）】
 * - **建流之前**的错误（42210 参数校验 / 40301 越权 / 42200 节点不可用 / 42214 引用落空）：
 *   HTTP 状态码与 JSON 响应体都还是"正常"的，由 `error` 暴露给调用方去做表单回填；
 * - **建流之后**的错误（并发已满 42900、执行期异常）：响应头早已发出，改不了状态码，
 *   只能走流里的 `ERROR` 帧 —— 同样进 `error`，但调用方此时已无表单可回填。
 * 两者对调用方暴露为同一个 `error`，但 `streamStarted` 能区分"是不是已经开跑"。
 */

const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1'

/** 32 位无连字符小写 hex，与 api/http.ts 的 traceId 口径一致（I-01）。 */
function newTraceId(): string {
  const raw = crypto.randomUUID?.() ?? `${Date.now()}${Math.random()}`
  return raw.replace(/-/g, '').slice(0, 32).padEnd(32, '0')
}

/** 从一段 SSE 事件块里取出 `data:` 负载（按规范可以有多行，用 \n 连接）。 */
function extractData(block: string): string | null {
  const parts: string[] = []
  for (const rawLine of block.split('\n')) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line.startsWith('data:')) {
      // 规范允许 `data:` 后跟一个空格，去掉它；其余空白属于负载本身
      parts.push(line.slice(5).replace(/^ /, ''))
    }
  }
  return parts.length ? parts.join('\n') : null
}

interface DryRunError {
  code: number
  message: string
}

interface DryRunStreamState {
  /** CMD 帧：本次将执行什么（命令已脱敏） */
  command: Ref<DryRunCmdFrame | null>
  /** 实时日志行（seq 单调，已按 seq 去重） */
  lines: Ref<LogLine[]>
  /** EOF 帧：退出码与耗时（`exitCode === null` 表示进程未能确认启动） */
  eof: Ref<DryRunEofFrame | null>
  /** 错误（建流前或流内 ERROR 帧，来源用 `streamStarted` 区分） */
  error: Ref<DryRunError | null>
  /** 是否正在跑 */
  running: Ref<boolean>
  /** 是否已经建立过流（= 远端可能已经开始执行，此时错误不再是"表单问题"） */
  streamStarted: Ref<boolean>
  start: (versionId: string, params: DryRunParams) => Promise<void>
  stop: () => void
}

export function useDryRunStream(): DryRunStreamState {
  const command = ref<DryRunCmdFrame | null>(null)
  const lines = ref<LogLine[]>([])
  const eof = ref<DryRunEofFrame | null>(null)
  const error = ref<DryRunError | null>(null)
  const running = ref(false)
  const streamStarted = ref(false)

  const auth = useAuthStore()
  let controller: AbortController | null = null
  let lastSeq = 0

  function reset() {
    command.value = null
    lines.value = []
    eof.value = null
    error.value = null
    streamStarted.value = false
    lastSeq = 0
  }

  function handleBlock(block: string) {
    const payload = extractData(block)
    if (!payload) return
    let parsed: unknown
    try {
      parsed = JSON.parse(payload)
    } catch {
      return // 非 JSON 心跳之类：忽略（不让一行脏数据打断整个流）
    }
    // 帧的键名是 snake_case（后端 Jackson 全局策略），这里补上"拦截器以外"的转换。
    // 注意 deepCamel 会跳过 params/sources 这类"用户数据容器"的内部键（见 utils/casename.ts）
    const frame = deepCamel<{ type: DryRunFrameType } & Record<string, unknown>>(parsed)
    switch (frame.type) {
      case 'CMD':
        command.value = frame as unknown as DryRunCmdFrame
        break
      case 'LOG': {
        const log = frame as unknown as DryRunLogFrame
        // 与后端"不重不丢"口径双保险：seq 非递增的帧丢弃
        if (typeof log.seq === 'number' && log.seq > lastSeq) {
          lastSeq = log.seq
          lines.value.push({ seq: log.seq, stream: log.stream, content: log.content })
        }
        break
      }
      case 'EOF':
        eof.value = frame as unknown as DryRunEofFrame
        running.value = false
        break
      case 'ERROR': {
        const errFrame = frame as unknown as DryRunErrorFrame
        error.value = { code: errFrame.code, message: errFrame.message }
        running.value = false
        break
      }
      default:
        break
    }
  }

  async function start(versionId: string, params: DryRunParams): Promise<void> {
    stop()
    reset()
    running.value = true
    controller = new AbortController()
    const signal = controller.signal

    let response: Response
    try {
      response = await fetch(`${API_BASE}/operator-versions/${versionId}/dry-run`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          Accept: 'text/event-stream',
          'X-Trace-Id': newTraceId(),
          ...(auth.token ? { Authorization: `Bearer ${auth.token}` } : {}),
        },
        // 请求出口同样要 snake（后端 Jackson 全局 SNAKE_CASE）；params 的内部键会被保护
        body: JSON.stringify(deepSnake(params)),
        signal,
      })
    } catch (e) {
      // 主动 stop() 会走到这里：安静返回，不当作错误
      if (signal.aborted) return
      running.value = false
      error.value = { code: 0, message: '网络异常，请检查连接后重试' }
      throw e
    }

    const contentType = response.headers.get('content-type') ?? ''
    if (!response.ok || !contentType.includes('text/event-stream')) {
      // 建流失败：后端此时给的是普通 ApiResult JSON（让用户回去改表单）
      running.value = false
      error.value = await readPreStreamError(response)
      return
    }

    streamStarted.value = true

    const body = response.body
    if (!body) {
      running.value = false
      error.value = { code: 0, message: '响应没有可读的流' }
      return
    }

    const reader = body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        // Spring 的 SseEmitter 用 LF 分帧；顺手把 CRLF 归一，避免混用格式时漏帧
        buffer = buffer.replace(/\r\n/g, '\n')
        let sep = buffer.indexOf('\n\n')
        while (sep >= 0) {
          const block = buffer.slice(0, sep)
          buffer = buffer.slice(sep + 2)
          handleBlock(block)
          sep = buffer.indexOf('\n\n')
        }
      }
      // 流自然结束但没收到 EOF：当作异常收尾，避免按钮一直转
      if (running.value) {
        running.value = false
        if (!error.value) {
          error.value = { code: 0, message: '连接已断开，未收到结束帧' }
        }
      }
    } catch (e) {
      if (signal.aborted) return
      running.value = false
      error.value = { code: 0, message: '日志流读取中断' }
      throw e
    }
  }

  /** 建流前的错误体是标准 ApiResult（{code,message,data,trace_id}）。 */
  async function readPreStreamError(response: Response): Promise<DryRunError> {
    try {
      const raw: unknown = await response.json()
      const payload = deepCamel<{ code?: number; message?: string }>(raw)
      if (typeof payload?.code === 'number') {
        return { code: payload.code, message: payload.message ?? '试运行失败' }
      }
    } catch {
      // 响应体不是 JSON（如网关的 HTML 错误页）：落到下面的兜底
    }
    return { code: response.status, message: `试运行失败（HTTP ${response.status}）` }
  }

  function stop() {
    controller?.abort()
    controller = null
    running.value = false
  }

  onUnmounted(stop)

  return { command, lines, eof, error, running, streamStarted, start, stop }
}
