import { createApp } from 'vue'
import App from './App.vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import router from './router'

import './styles/index.css' // ① 令牌 + 重置 + 氛围层（必须先于 Element Plus）
import 'element-plus/dist/index.css' // ② Element Plus 基础样式
import './styles/element-override.scss' // ③ 覆盖（必须最后，方能生效）

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn, size: 'small' }) // small 对齐 DESIGN-01 的 33px 控件高
app.mount('#app')
