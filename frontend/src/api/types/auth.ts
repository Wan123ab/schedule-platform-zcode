/** 认证域类型（docs/07 §5.1 / §5.4）。 */
export interface LoginParams {
  username: string
  password: string
}

export interface LoginResult {
  tokenName: string
  tokenValue: string
  user: {
    userId: string
    username: string
    displayName: string
    roles: string[]
    scopeTypes: string[]
  }
  permissions: string[]
}
