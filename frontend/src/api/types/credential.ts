/**
 * 凭据域类型（docs/07 §6.2 / CONTRACT §4）。
 *
 * 【安全红线】这里<b>没有</b> secret 的读取字段 —— 出参只有 `secretFingerprint`（后 4 位掩码）。
 * 入参 `SaveCredentialParams.secret` 是 write-only：只在创建/轮换时上行，永不回显。
 * 类型层面缺失读字段，比"文档里写一句不要回显"可靠得多。
 */
export interface CredentialItem {
  credentialId: string
  credentialName: string
  /** SSH_KEY / USER_PASSWORD / WINRM / TOKEN */
  credentialType: string
  username: string | null
  /** 形如 ****a1b2（后端已掩码，前端不再处理） */
  secretFingerprint: string
  /** 归属项目的**业务编号**（PRJ-xxxx）；平台级凭据为 null */
  projectId: string | null
  /** 归属项目名（平台级为 null） */
  projectName: string | null
  /** 引用计数快照（展示用；是否可删以服务端实时 COUNT 为准） */
  refCount: number
  /** VALID / EXPIRING / EXPIRED / REVOKED */
  status: string
  lastRotatedAt: string | null
  expireAt: string | null
  description: string | null
}

export interface SaveCredentialParams {
  credentialName: string
  credentialType: string
  username?: string
  /** write-only：仅在创建与轮换时上行 */
  secret?: string
  /** 归属项目的业务编号（PRJ-xxxx）；留空 = 平台级凭据 */
  projectId?: string | null
  expireAt?: string | null
  description?: string
}
