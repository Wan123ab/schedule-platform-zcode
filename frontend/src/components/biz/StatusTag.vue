<script setup lang="ts">
/**
 * StatusTag —— 任务/步骤状态徽标（docs/04 §6.2）。
 *
 * 【本组件现在只做一件事：查表】
 * 把业务状态（任务 9 态 / 步骤 11 态）翻成 `tone + marker + label`，然后交给
 * ToneChip 渲染。视觉细节（圆角/混色/动画）全部收在 ToneChip 里，
 * 这样新增状态域时只需要加映射表，不用碰任何样式 —— 这是 D-26 ②「能复用就封装」的落地：
 * 复用发生在"行为与外观"两端，而不是把同一段 <span> 标记抄到每个域。
 *
 * 【Vue 机制说明】
 * - `<script setup>`：顶层 import / 变量 / 函数直接在 <template> 里可用，无需注册组件。
 * - defineProps：声明入参（类似函数参数）。`kind` 用联合类型收窄，传错组合编译期即报错。
 * - computed：由其他响应式值推导，依赖不变就不重算（自带缓存）。
 */
import { computed } from 'vue'
import ToneChip from '@/components/biz/ToneChip.vue'
import {
  STEP_STATUS_LABEL,
  STEP_STATUS_MARKER,
  STEP_STATUS_TONE,
  TASK_STATUS_LABEL,
  TASK_STATUS_MARKER,
  TASK_STATUS_TONE,
  type Marker,
  type StepStatus,
  type TaskStatus,
  type Tone,
} from '@/types/enums'

const props = defineProps<{ status: TaskStatus | StepStatus; kind?: 'TASK' | 'STEP' }>()

const isStep = computed(() => props.kind === 'STEP')

// 查表：`?? 'idle'` 是防御性兜底 —— 后端若新增了前端还没映射的状态，也不会渲染成空白
const tone = computed<Tone>(() =>
  isStep.value
    ? (STEP_STATUS_TONE[props.status as StepStatus] ?? 'idle')
    : (TASK_STATUS_TONE[props.status as TaskStatus] ?? 'idle'),
)
const marker = computed<Marker>(() =>
  isStep.value
    ? (STEP_STATUS_MARKER[props.status as StepStatus] ?? 'dot')
    : (TASK_STATUS_MARKER[props.status as TaskStatus] ?? 'dot'),
)
const label = computed(() =>
  isStep.value
    ? (STEP_STATUS_LABEL[props.status as StepStatus] ?? String(props.status))
    : (TASK_STATUS_LABEL[props.status as TaskStatus] ?? String(props.status)),
)
</script>

<template>
  <ToneChip :tone="tone" :marker="marker" :label="label" />
</template>
