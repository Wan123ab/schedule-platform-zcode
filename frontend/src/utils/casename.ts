/**
 * snake ↔ camel 转换（D-15，docs/04 §4.4）：
 * 请求出口 deepSnake、响应入口 deepCamel，转换只在 axios 拦截器一处；
 * TS 类型与业务代码全 camelCase。
 */
export const toCamel = (s: string): string => s.replace(/_([a-z0-9])/g, (_, c: string) => c.toUpperCase())
export const toSnake = (s: string): string => s.replace(/[A-Z]/g, (c) => '_' + c.toLowerCase())

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
      out[convert(k)] = walk(v, convert)
    }
    return out
  }
  return input
}
