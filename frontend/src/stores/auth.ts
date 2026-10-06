import { defineStore } from 'pinia'

const TOKEN_KEY = 'flowops.token'

/** auth store（docs/04 §5.1）：user、roles、permissions、token 存在标记。 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    userId: '',
    username: '',
    displayName: '',
    roles: [] as string[],
    scopeTypes: [] as string[],
    permissions: [] as string[],
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
  },
  actions: {
    setSession(payload: {
      token: string
      userId: string
      username: string
      displayName: string
      roles: string[]
      scopeTypes: string[]
      permissions: string[]
    }) {
      this.token = payload.token
      this.userId = payload.userId
      this.username = payload.username
      this.displayName = payload.displayName
      this.roles = payload.roles
      this.scopeTypes = payload.scopeTypes
      this.permissions = payload.permissions
      localStorage.setItem(TOKEN_KEY, payload.token)
    },
    reset() {
      this.token = ''
      this.permissions = []
      localStorage.removeItem(TOKEN_KEY)
    },
  },
})
