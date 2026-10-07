/**
 * 展示层格式化工具（M2 新增）。
 *
 * 【为什么单独一个文件而不是写在组件里】
 * "MB 换成 GB""时间显示成 2026-10-07 11:20"这类转换会在列表、详情、卡片里反复出现。
 * 一旦散落各处，同一次改版就会出现"这页留 1 位小数、那页留 0 位"的差别。
 * 统一在这里，页面只负责取数。
 */

/** MB → 人类可读（< 1024 显示 MB，否则 GB）；undefined/null 显示 — */
export function formatMb(mb?: number | null): string {
  if (mb === null || mb === undefined) return '—'
  if (mb < 1024) return `${mb} MB`
  return `${(mb / 1024).toFixed(mb / 1024 >= 100 ? 0 : 1)} GB`
}

/** 后端返回 ISO 串（OffsetDateTime）→ 本地 `YYYY-MM-DD HH:mm`；空值显示 — */
export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 心跳时间 → 相对描述（运维最关心"最后一次心跳是多久以前"）。
 * 阈值与调度器 HeartbeatScanner 的三段口径一致（15s 上报 / 45s 判离线 / 5min 强离线）：
 * 文案里把 45s 这条线显式说出来，避免运维看到 "2 分钟前" 还以为节点是健康的。
 */
export function formatHeartbeat(value?: string | null): string {
  if (!value) return '从未上报'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const seconds = Math.max(0, Math.floor((Date.now() - date.getTime()) / 1000))
  if (seconds < 60) return `${seconds} 秒前`
  const minutes = Math.floor(seconds / 60)
  // 走到这里必然 >= 1 分钟 → 必然已越过 45s 离线线，无需再判一次
  if (minutes < 60) return `${minutes} 分钟前（已超离线阈值）`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return formatDateTime(value)
}

/** 数值列的安全展示（null → —，避免表格里出现 "null"） */
export function dash(value?: string | number | null): string {
  return value === null || value === undefined || value === '' ? '—' : String(value)
}
