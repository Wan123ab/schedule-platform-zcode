<script setup lang="ts">
/**
 * ToneChip —— 通用状态徽标（M2 从 StatusTag 里抽出）。
 *
 * 【抽取的理由（D-26 ②：能复用就封装）】
 * 任务/步骤之外，M2 又多了集群、节点在线、队列、凭据四类状态，它们要的是<b>同一套</b>
 * 「tone 颜色 + marker 形态」视觉；如果每个域各写一遍 <span class="st">，
 * 全站状态徽标的圆角/字号/混色比例就会慢慢漂移。
 *
 * 【职责边界】
 * - ToneChip：只认 `tone + label + marker`，是纯展示组件（不知道任何业务枚举）。
 * - 各域的映射表（types/enums.ts 的 *_STATUS_TONE/_LABEL）：负责业务枚举 → tone/label。
 * - StatusTag：任务/步骤的适配壳，把 status 查表后交给 ToneChip。
 * 这样"新增一个状态域"= 加一组映射表 + 直接 <ToneChip>，不需要动样式。
 *
 * 【marker 形态的用意】颜色之外再给一个形状信号：
 * 色弱用户分不清红绿，但分得清「✓ 对勾」和「✕ 叉」；扫视时形状也比色块更快被识别。
 */
import { computed } from 'vue'
import type { Marker, Tone } from '@/types/enums'

const props = defineProps<{
  tone: Tone
  label: string
  marker?: Marker
}>()

/** tone → tokens.css 的 CSS 变量（禁止硬编码色值，F-2：视觉令牌单一真源） */
const TONE_VAR: Record<Tone, string> = {
  ok: 'var(--ok)',
  fail: 'var(--fail)',
  warn: 'var(--warn)',
  info: 'var(--info)',
  mut: 'var(--violet)',
  idle: 'var(--t3)',
}

const color = computed(() => TONE_VAR[props.tone] ?? 'var(--t3)')
const marker = computed<Marker>(() => props.marker ?? 'dot')
</script>

<template>
  <!-- soft 底 + 同色描边 + 同色文字；color-mix 按比例混出浅底（现代浏览器基线内） -->
  <span
    class="chip"
    :style="{ color, borderColor: color, background: `color-mix(in srgb, ${color} 13%, transparent)` }"
  >
    <span v-if="marker === 'check'" class="mk">✓</span>
    <span v-else-if="marker === 'cross'" class="mk">✕</span>
    <span v-else-if="marker === 'square'" class="mk mk-square" :style="{ background: color }" />
    <span v-else-if="marker === 'pulse'" class="mk mk-pulse" :style="{ background: color }" />
    <span
      v-else-if="marker === 'spin'"
      class="mk mk-spin"
      :style="{ borderColor: color, borderTopColor: 'transparent' }"
    />
    <span v-else-if="marker === 'clock'" class="mk mk-clock" :style="{ borderColor: color }" />
    <span v-else class="mk mk-dot" :style="{ background: color }" />
    {{ label }}
  </span>
</template>

<style scoped>
.chip {
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

.mk-square {
  border-radius: 1px;
}

.mk-pulse {
  border-radius: 50%;
  animation: chip-pulse 1.6s infinite;
}

.mk-spin {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 2px solid;
  animation: chip-spin 1s linear infinite;
}

.mk-clock {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 1.5px solid;
  background: transparent;
}

@keyframes chip-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

@keyframes chip-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
