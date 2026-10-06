import { defineStore } from 'pinia'

export type ViewMode = 'business' | 'ops'

/** app store（docs/04 §5.1）：主题、侧栏折叠、视图模式。 */
export const useAppStore = defineStore('app', {
  state: () => ({
    theme: (localStorage.getItem('flowops.theme') || 'dark') as 'dark' | 'light',
    viewMode: (localStorage.getItem('flowops.viewMode') || 'business') as ViewMode,
    sidebarCollapsed: false,
  }),
  actions: {
    setTheme(theme: 'dark' | 'light') {
      this.theme = theme
      localStorage.setItem('flowops.theme', theme)
      document.body.setAttribute('data-theme', theme)
    },
    setViewMode(mode: ViewMode) {
      this.viewMode = mode
      localStorage.setItem('flowops.viewMode', mode)
      document.body.setAttribute('data-view-mode', mode)
    },
  },
})
