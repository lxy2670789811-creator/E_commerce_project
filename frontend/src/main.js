import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)
// Element Plus 全量引入 + 中文语言包
app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
