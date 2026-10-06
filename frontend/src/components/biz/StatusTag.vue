<script setup lang="ts">
/**
 * StatusTag —— 全站唯一的状态徽标渲染口径（docs/04 §6.2 / DESIGN-01 §2.5）。
 *
 * 【为什么有这个组件】
 * 每个列表/卡片里最先被注意到的应该是"什么东西不正常"。状态的可读性靠两层叠加：
 *   ① 颜色（tone）    —— 成功绿 / 失败红 / 警告黄 / 信息蓝 / 中性灰
 *   ② 形态（marker）  —— 实心点 / 脉冲 / 旋转 / ✓ / ✕ / 时钟 / 方块
 * 只有颜色没有形态，色弱用户无法区分；只有形态没有颜色，扫视效率低。
 * tone 与 marker 的取值集中在 types/enums.ts 的映射表里，本组件只负责"查表 + 渲染"，
 * 业务代码永远不写 if-else 决定颜色（F-5：枚举标签/颜色只能来自映射表）。
 *
 * 【Vue 机制说明】
 * - `<script setup>`：Vue 3 组合式写法，顶层 import / 变量 / 函数直接在 <template> 里可用，
 *   无需再写 export default 与 components 注册。
 * - defineProps：声明本组件的"入参"（类似函数参数）。父组件写 <StatusTag :status="row.status" />。
 * - computed：由其他响应式值"推导"出的值，依赖不变就不重算（自带缓存）。
 * - <style scoped>：样式只作用于本组件，编译时加上唯一属性选择器，不会污染其他页面。
 */
import { computed } from 'vue'
import {
  STEP_STATUS_LABEL,
  STEP_STATUS_MARKER,
  STEP_STATUS_TONE,
  TASK_STATUS_LABEL,
  TASK_STATUS_MARKER,
  TASK_STATUS_TONE,
  type StepStatus,
  type TaskStatus,
} from '@/types/enums'

/**
 * kind 决定查哪张映射表：任务 9 态 / 步骤 11 态（docs/04 §2.4）。
 * TS 侧用联合类型收窄：传错 kind 与 status 的组合会在编译期暴露。
 */
const props = defineProps<{ status: TaskStatus | StepStatus; kind?: 'TASK' | 'STEP' }>()

/** 查映射表得到颜色 tone，再翻译成 tokens.css 里的 CSS 变量名（禁止硬编码色值，F-2） */
const TONE_VAR: Record<string, string> = {
  ok: 'var(--ok)',
  fail: 'var(--fail)',
  warn: 'var(--warn)',
  info: 'var(--info)',
  mut: 'var(--violet)',
  idle: 'var(--t3)',
}

const tone = computed(() =>
  props.kind === 'STEP'
    ? (STEP_STATUS_TONE[props.status as StepStatus] ?? 'idle')
    : (TASK_STATUS_TONE[props.status as TaskStatus] ?? 'idle'),
)
const marker = computed(() =>
  props.kind === 'STEP'
    ? (STEP_STATUS_MARKER[props.status as StepStatus] ?? 'dot')
    : (TASK_STATUS_MARKER[props.status as TaskStatus] ?? 'dot'),
)
const label = computed(() =>
  props.kind === 'STEP'
    ? (STEP_STATUS_LABEL[props.status as StepStatus] ?? String(props.status))
    : (TASK_STATUS_LABEL[props.status as TaskStatus] ?? String(props.status)),
)
const color = computed(() => TONE_VAR[tone.value] ?? 'var(--t3)')
</script>

<template>
  <!-- soft 底 + 同色描边 + 同色文字：颜色由 CSS 变量驱动，color-mix 按比例混出浅底（现代浏览器基线内） -->
  <span class="st" :style="{ color, borderColor: color, background: `color-mix(in srgb, ${color} 13%, transparent)` }">
    <!-- 形态标记：不同 marker 渲染不同结构（见下方 v-if 分支与样式） -->
    <span v-if="marker === 'check'" class="mk">✓</span>
    <span v-else-if="marker === 'cross'" class="mk">✕</span>
    <span v-else-if="marker === 'pulse'" class="mk mk-pulse" :style="{ background: color }" />
    <span v-else-if="marker === 'spin'" class="mk mk-spin" :style="{ borderColor: color, borderTopColor: 'transparent' }" />
    <span v-else-if="marker === 'clock'" class="mk mk-clock" :style="{ borderColor: color }" />
    <span v-else class="mk mk-dot" :style="{ background: color }" />
    {{ label }}
  </span>
</template>

<style scoped>
.st {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  line-height: 1;
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid;
  white-space: nowrap;
}

.mk {
  display: inline-block;
  width: 6px;
  height: 6px;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
}

/* 脉冲 = 呼吸闪烁（SCHEDULING / STOPPING：'还在动'的语义） */
.mk-pulse {
  border-radius: 50%;
  animation: st-pulse 1.6s infinite;
}

/* 旋转 = 进行动（RUNNING） */
.mk-spin {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 2px solid;
  animation: st-spin 1s linear infinite;
}

/* 时钟轮廓 = 超时类（TIMEOUT） */
.mk-clock {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 1.5px solid;
  background: transparent;
}

@keyframes st-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

@keyframes st-spin {
  to { transform: rotate(360deg); }
}
</style>
