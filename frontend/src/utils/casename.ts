/**
 * snake ↔ camel 转换（D-15，docs/04 §4.4）：
 * 请求出口 deepSnake、响应入口 deepCamel，转换只在 axios 拦截器一处；
 * TS 类型与业务代码全 camelCase。
 */
export const toCamel = (s: string): string => s.replace(/_([a-z0-9])/g, (_, c: string) => c.toUpperCase())
export const toSnake = (s: string): string => s.replace(/[A-Z]/g, (c) => '_' + c.toLowerCase())

/**
 * 「值语义容器」键名 —— 它们**内部**的键是**用户数据**，不是协议字段，**不参与转换**。
 *
 * 【为什么需要这个例外（D-15 的边界）】
 * D-15 说"键转换只在拦截器一处"，其前提是"对象里的键都是协议字段"。但有一类字段例外：
 * 它们承载的是**用户自己起的名字**，后端原样存取、Jackson 的命名策略也管不到
 * （`PropertyNamingStrategy` 只作用于 POJO 属性，不作用于 `Map` 的 key）。
 *
 * 典型的 `param_key`：后端 `OperatorVersionValidator` 的 `IDENTIFIER` 是
 * `[a-zA-Z_][a-zA-Z0-9_]*` —— **允许大写**。于是 `inputPath` 这种完全合法的参数名
 * 会被 `deepSnake` 改成 `input_path`，后端拿去和模板比对时找不到 → 误报
 * "模板外参数"（42210）；响应方向同理，`deepCamel` 会把 `input_path` 改回 `inputPath`，
 * 前端也读不出原始名字。**用户没写错，是转换器改错了**。
 *
 * 收录判据是「**这个字段的值的键是用户数据**」，不是名字好不好看：
 * - `DryRunRequest.params` / `SaveDefaultParamsRequest.params` / `DagStepDef.params`
 *   —— 键是 `param_key`（用户填，允许大写）
 * - `DagStepDef.customParams` —— 键是用户自定义参数名
 * - `SaveTriggerRequest.runParams` / `TriggerVO.runParams` —— 键是触发时参数名
 * - `SaveProjectRequest.defaultParams` —— 键是项目级参数名
 * - `SaveWorkflowVersionRequest.workflowParams` / `WorkflowVersionVO.workflowParams` —— 元素是参数名→值
 * - 试运行 CMD 帧的 `sources` —— 键同样是 `param_key`（值是来源层名）
 *
 * 判断前统一 `toCamel` 归一，故两个方向（camel 键与 snake 键）都能命中。
 */
const OPAQUE_VALUE_KEYS: ReadonlySet<string> = new Set([
  'params',
  'customParams',
  'runParams',
  'workflowParams',
  'defaultParams',
  'sources',
])

export function deepCamel<T>(input: unknown): T {
  return walk(input, toCamel) as T
}

export function deepSnake(input: unknown): unknown {
  return walk(input, toSnake)
}

function walk(input: unknown, convert: (s: string) => string): unknown {
  if (input === null || input === undefined) return input
  if (Array.isArray(input)) return input.map((v) => walk(v, convert))
  if (input instanceof Date || input instanceof File || input instanceof Blob || input instanceof FormData) {
    return input
  }
  if (typeof input === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(input as Record<string, unknown>)) {
      // 容器键名本身照转（'params' 转完还是 'params'），但其**值**整体原样搬走、不递归
      out[convert(k)] = OPAQUE_VALUE_KEYS.has(toCamel(k)) ? v : walk(v, convert)
    }
    return out
  }
  return input
}
