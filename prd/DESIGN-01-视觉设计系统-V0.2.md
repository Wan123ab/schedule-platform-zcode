# FlowOps 视觉设计系统 V0.2 ·「深空控制台 / Deep Space Console」

> 定位：本文件是**原型与前端的唯一视觉源头**。所有页面的颜色、字体、间距、圆角、光效只能引用本文件的令牌，禁止局部硬编码。
> 适用：`智能工作流调度与监控平台` 全部原型页面（16 个页面 / 7 个批次）。
> 参照实现：`prototype/task-list.html`（批次 0 黄金页面，可直接对读）。

---

## 0. 为什么重做

V0.1 原型采用 Ant Design 5 默认浅色体系（`#1677FF` + `#FAFAFA` + 6px 圆角）。功能完备，但存在三个致命问题：

| 问题 | 后果 |
|---|---|
| 与 Ant 后台模板零差异 | 评审时"看起来像随便找的模板"，无法体现产品专业度 |
| 纯平面、无层次 | 信息密度高的调度数据看不出主次关系 |
| 无状态表现力 | 「运行中」和「已停止」在视觉上几乎等价，监控平台最核心的能力反而最弱 |

**V0.2 的判断：这是一个 7×24 小时值守的运维监控系统，应该长成"控制台"，而不是"管理后台"。**

---

## 1. 设计定位

**深空控制台** —— 深色基底、数据发光、层级靠光而非靠线。

四条原则：

1. **数据是主角，装饰是配角**：发光只用在状态与关键指标上；正文、表头、辅助信息一律低对比，把注意力留给数字。
2. **层级靠明度，不靠描边**：五级背景明度（void → inset）承担主要分层，边框只做极弱的分隔暗示。
3. **等宽治数字**：所有编号、时间、耗时、计数一律等宽 +`tabular-nums`，保证纵向对齐——这是"专业感"最便宜也最有效的来源。
4. **克制发光**：同一屏内高亮元素不超过 3 处。到处发光等于都不发光。

---

## 2. 色彩系统

### 2.1 层次背景（五级明度，深色主题）

| 令牌 | 值 | 用途 |
|---|---|---|
| `--bg-void` | `#04060B` | 最深底（遮罩、极深容器） |
| `--bg-base` | `#070A11` | 页面主背景 |
| `--bg-panel` | `#0C111A` | 卡片 / 面板 / 表格底 |
| `--bg-raise` | `#111827` | 次级面板、按钮、输入框 |
| `--bg-hover` | `#161F30` | 悬浮态 |
| `--bg-inset` | `#090E16` | 内嵌区（表头、代码块、搜索框） |

> 规则：**嵌套层级每深一层，明度下降一档**。面板内嵌区域用 `--bg-inset`，不要用透明度叠加。

### 2.2 描边

| 令牌 | 值 | 用途 |
|---|---|---|
| `--line-faint` | `rgba(148,163,184,.07)` | 表格行分隔、极弱分区 |
| `--line` | `rgba(148,163,184,.12)` | 卡片、输入框常规边框 |
| `--line-strong` | `rgba(148,163,184,.22)` | 悬浮、强调边框 |

> 禁止使用实色边框（如 `#333`）。深色界面里实色边框会显脏。

### 2.3 文本层级

| 令牌 | 值 | 用途 |
|---|---|---|
| `--t1` | `#E8EEF9` | 标题、表格主字段、关键数字 |
| `--t2` | `#93A2B8` | 正文、次字段、按钮文字 |
| `--t3` | `#5D6B84` | 标签、辅助说明、表头 |
| `--t4` | `#39435A` | 禁用、占位符 |

### 2.4 主色（电光蓝）

| 令牌 | 值 | 用途 |
|---|---|---|
| `--pri` | `#2E90FA` | 主操作、选中、链接 |
| `--pri-hi` | `#5FAEFF` | 悬浮态、强调文字 |
| `--pri-lo` | `#1A6ED8` | 渐变深端 |
| `--pri-dim` | `rgba(46,144,250,.12)` | 主色浅底（选中背景、tag 底） |
| `--pri-line` | `rgba(46,144,250,.34)` | 主色边框 |

科技点缀色（仅用于渐变、图表、Logo，**不用于语义**）：`--cyan: #22D3EE`、`--violet: #A78BFA`。

### 2.5 语义状态色 ★ 核心

每个语义色由三元组构成：`色值 / dim 底 / line 边`。

| 语义 | tone 名 | 主色 | dim 底 | 边框 | 映射业务状态 |
|---|---|---|---|---|---|
| 进行中 | `info` | `#3B9BFF` | `rgba(59,155,255,.13)` | `rgba(59,155,255,.32)` | RUNNING / SCHEDULING |
| 成功 | `ok` | `#28C76F` | `rgba(40,199,111,.13)` | `rgba(40,199,111,.30)` | SUCCESS |
| 警告 | `warn` | `#F5A524` | `rgba(245,165,36,.13)` | `rgba(245,165,36,.30)` | TIMEOUT / PARTIAL |
| 失败 | `fail` | `#F5596F` | `rgba(245,89,111,.13)` | `rgba(245,89,111,.32)` | FAILED |
| 静默 | `idle` | `#8296AE` | `rgba(130,150,174,.14)` | `rgba(130,150,174,.28)` | PENDING / STOPPED |

**状态标记形态（marker）规范**——同一状态在全局必须用同一形态：

| 状态 | tone | marker | 视觉 |
|---|---|---|---|
| 等待中 PENDING | idle | `dot` | 静态灰点 |
| 调度中 SCHEDULING | info | `pulse` | 蓝色呼吸光环（`halo` 动画 1.8s） |
| 运行中 RUNNING | info | `spin` | 蓝色旋转环 |
| 成功 SUCCESS | ok | `check` | 对勾 ✓ |
| 失败 FAILED | fail | `cross` | 叉 ✗ |
| 超时 TIMEOUT | warn | `clock` | 时钟 |
| 已停止 STOPPED | idle | `square` | 实心方 |
| 部分成功 PARTIAL | warn | `dot` | 静态橙点 |

> **注意：本项目为运维调度语义，成功=绿 / 失败=红。这与 A 股「红涨绿跌」相反，两者不可混用——若后续出现行情类页面，必须单独定义色板。**

### 2.6 浅色主题

通过 `body[data-theme="light"]` 覆盖同一批令牌，底色用冷灰 `#F2F5FA` 而非纯白，避免刺眼。

关键覆盖：

```css
--bg-base:#F2F5FA; --bg-panel:#FFFFFF; --bg-inset:#F7F9FD;
--t1:#0E1729; --t2:#4A5A73; --t3:#7A889E;
--info:#1E7BE0; --ok:#12A05A; --warn:#C97F0A; --fail:#DD3B52; --idle:#5D6B84;
--grid-a:.035; --glow-a:.07;   /* 网格与光晕强度减半 */
```

**要求**：所有页面必须通过顶栏的明/暗开关可切换，且两种主题下均需人工检查一遍对比度。

---

## 3. 字体与排版

```css
--font:"PingFang SC","HarmonyOS Sans SC","Microsoft YaHei UI","Microsoft YaHei",system-ui,sans-serif;
--mono:"JetBrains Mono","Cascadia Mono","SF Mono",Consolas,monospace;
```

字号阶梯（**项目内只允许出现这 7 档**）：

| 档位 | 字号 | 行高 | 用途 |
|---|---|---|---|
| 大标题 | 22px / 600 | 1.3 | 页面标题 |
| 中标题 | 14.5px / 600 | 1.4 | 弹窗标题 |
| 小标题 | 13.5px / 600 | 1.4 | 品牌名、分区标题 |
| 正文 | 12.5px / 400 | 1.6 | 表格、表单、正文 |
| 辅助 | 11.5px / 400 | 1.5 | 表格内次要文字、按钮 |
| 标签 | 11px / 500 | 1.4 | 表头、字段 label（配 `letter-spacing:.04em`） |
| 微型 | 9.5px / 400 | 1.4 | 品牌副标、dock 标识（配 `letter-spacing:.15em`，等宽） |

**数字规范**：
- 所有编号 / 时间 / 耗时 / 计数使用 `--mono` + `font-variant-numeric:tabular-nums`
- 关键指标数字字号 25px、字重 600、`letter-spacing:-.025em`（负字距让数字更紧凑、更有"仪表感"）
- body 全局开启 `font-feature-settings:"tnum" 1`

---

## 4. 形状与间距

```css
--r-xs:4px;   /* 行内小按钮、tag */
--r-sm:7px;   /* 按钮、输入框、导航项 */
--r:10px;     /* 次级容器 */
--r-lg:14px;  /* 卡片、面板 */
--r-xl:18px;  /* 弹窗 */
```
胶囊形（`border-radius:100px`）保留给：状态徽标、Toast、指标卡顶部圆点、dock。

**间距基数 4px**，常用档位：4 / 6 / 8 / 11 / 13 / 15 / 17 / 20 / 22。

骨架尺寸（所有页面统一，不得随意调整）：
- 侧边栏 `230px`
- 顶栏 `62px`
- 内容区左右内边距 `22px`
- 表格行高 `≈40px`（`padding:9px 13px` + 内容）
- 表单控件高度 `33px`

---

## 5. 质感与光效 ★ 科技感的主要来源

按重要程度排序，前三条必须实现：

### 5.1 氛围背景（必做）

```css
body::before{           /* 三层径向光晕 */
  background:
    radial-gradient(1100px 560px at 10% -14%,rgba(46,144,250,var(--glow-a)),transparent 62%),
    radial-gradient(900px 480px at 94% -8%,rgba(167,139,250,calc(var(--glow-a) * .8)),transparent 58%),
    radial-gradient(760px 620px at 52% 118%,rgba(34,211,238,calc(var(--glow-a) * .5)),transparent 62%);
}
body::after{            /* 56px 网格，向下淡出 */
  background-image:linear-gradient(rgba(148,163,184,var(--grid-a)) 1px,transparent 1px),
                   linear-gradient(90deg,rgba(148,163,184,var(--grid-a)) 1px,transparent 1px);
  background-size:56px 56px;
  mask-image:radial-gradient(ellipse 90% 70% at 50% 0%,#000 8%,transparent 76%);
}
```
`--glow-a` 深色 `.13` / 浅色 `.07`；`--grid-a` 深色 `.05` / 浅色 `.035`。

### 5.2 面板质感（必做）

```css
.panel{
  background:var(--bg-panel);
  border:1px solid var(--line);
  border-radius:var(--r-lg);
  box-shadow:inset 0 1px 0 rgba(255,255,255,.022),   /* 顶部内高光，模拟受光 */
             0 24px 54px -36px rgba(0,0,0,.98);      /* 极深大范围落影 */
}
/* 卡片额外叠加一层自上而下的白色微渐变 */
background:linear-gradient(180deg,rgba(255,255,255,.028),rgba(255,255,255,0) 62%),var(--bg-panel);
```

### 5.3 顶栏渐变分割线（必做）

一条 1px 的横向渐变线，两侧透明中间发光，是"科技控制台"的强识别符号：

```css
.topbar::after{
  background:linear-gradient(90deg,transparent,rgba(46,144,250,.5) 20%,rgba(167,139,250,.4) 62%,transparent);
}
```

### 5.4 状态发光

| 场景 | 实现 |
|---|---|
| 运行中状态点 | `animation:halo 1.8s ease-out infinite` 扩散光环 |
| 运行中表格行 | 左侧 2px 渐变竖条 + `box-shadow:0 0 12px rgba(46,144,250,.95)` + 行背景左向蓝色渐变 |
| 主按钮 | `box-shadow:0 9px 24px -11px rgba(46,144,250,.98),inset 0 1px 0 rgba(255,255,255,.26)` |
| 侧栏激活项 | 左条发光 + `inset` 描边 + 右侧 5px 光点 |
| 指标卡选中 | `--glow-pri` 外发光 + 主色顶条 |

### 5.5 指标卡迷你趋势图（sparkline）

每张指标卡底部内嵌 15 点 SVG 折线 + 面积渐变填充，宽度 100%、高度 24px、`vector-effect="non-scaling-stroke"` 保证线宽恒定。**这是让静态数据"看起来在流动"的最有效手段。**

---

## 6. 组件规范

### 6.1 按钮

| 类型 | 视觉 | 用途 |
|---|---|---|
| `.btn` | `--bg-raise` 底 + `--line` 边 | 次要操作 |
| `.btn-primary` | 蓝紫渐变 + 发光投影 + 顶部内高光 | 页面主操作（**每屏最多 1 个**） |
| `.btn-danger` | fail dim 底 + fail 边 | 危险操作（确认停止、删除） |
| `.btn-ghost` | 透明底，仅 hover 显形 | 重置、取消 |

统一：高度 33px、圆角 `--r-sm`、`transition:.16s`。

### 6.2 输入框 / 下拉

高度 33px，底 `--bg-inset`，边框 `--line`；聚焦时 `border-color:--pri` + `box-shadow:0 0 0 3px var(--pri-dim)`（**光晕式聚焦环，不用 outline**）。`select` 必须 `appearance:none` 并用内联 SVG 数据 URI 自定义箭头。

### 6.3 状态徽标 `.st`

胶囊形，`gap:6px`，高度 22px，结构 = `形态标记 + 文字`，配 `st-t-{tone}` 着色类。**禁止只靠颜色区分状态**，必须同时有形态差异（点/环/勾/叉）以满足色盲可达性。

### 6.4 表格

- 表头 `--bg-inset`，字号 11px，`letter-spacing:.04em`，sticky
- 数据行 `border-bottom:1px solid var(--line-faint)`，**不用竖线**
- 首列 sticky，且带一个 2px 左条 hover 显形（`::before`）
- 行 hover 整行 `--bg-hover`，首列同步（sticky 列必须手动同步背景色，否则会出现色块错位）
- 行内操作按钮默认 `opacity:.6`，行 hover 时提升到 `1` —— 降低静态视觉噪音
- **进度用进度条不用纯文字**：44px 细条 + 渐变填充 + `n/m` 等宽计数

### 6.5 弹窗

宽度 530px，圆角 `--r-xl`，顶部叠一层白色微渐变。遮罩 `rgba(3,6,11,.72)` + `backdrop-filter:blur(5px)`。进入动画 `pop .22s cubic-bezier(.2,.9,.3,1)`。头部带 30px 圆角色块图标（颜色随语义：危险=红、信息=蓝）。

### 6.6 Toast

胶囊形，位于顶部居中（`top:76px`），深色玻璃底 + 模糊，前置对勾图标，2.5s 自动消失。

---

## 7. 图标规范

- 统一 **16×16 viewBox**，`stroke-width:1.4`，`stroke-linecap:round`，`stroke-linejoin:round`，`fill:none`
- 徽标内的小图标用 9–11px，`stroke-width` 提到 1.5–1.7 保证可辨
- 全部内联 SVG，**禁止引入图标库 CDN**（内网/沙箱环境会失效）
- 导航图标语义固定：工作台=四宫格、任务=列表、工作流=DAG 路径、定时器=时钟、算子=立方体、节点=服务器、项目=文件夹、审计=盾牌、告警=柱状

---

## 8. 动效规范

| 名称 | 时长/曲线 | 用途 |
|---|---|---|
| `fadeUp` | .5s `cubic-bezier(.2,.9,.3,1)` | 页面元素入场（配合 `.stagger` 每项 +0.06s 递增延迟） |
| `pop` | .22s 同曲线 | 弹窗出现 |
| `halo` | 1.8s ease-out infinite | 运行中状态点呼吸 |
| `spin` | .8s linear infinite | 运行中旋转环 |
| `shimmer` | 1.5s ease infinite | 骨架屏流光 |
| `toastIn` | .24s | Toast 进入 |

**硬要求**：必须包含 `@media (prefers-reduced-motion:reduce)` 降级块，将所有动画压缩到 0.001s。

---

## 9. 落地约定

### 9.1 文件形态

单文件自包含 HTML（内联 CSS/JS，零外链、零 CDN），双击可开。原因：原型需分发给评审者，内网/反代/沙箱环境会拦截外部资源，导致样式全丢。

### 9.2 页面骨架（所有页面复用）

```html
<body data-theme="dark">
  <div class="app">
    <aside class="sidebar" data-block="sidebar">…</aside>
    <div class="main">
      <header class="topbar" data-block="topbar">…</header>
      <main class="content">
        <!-- 页面专属区块 -->
      </main>
    </div>
  </div>
  <div class="toast-wrap"></div>
  <div id="modalRoot"></div>
  <div class="dock">…演示控制台（正式交付删除）…</div>
</body>
```

### 9.3 `data-block` 锚点（迭代生命线）

每个可独立修改的区块必须标注 `data-block="kebab-case"`。后续修改使用**锚点定位**下指令：

> 正确：「只改 `data-block="filter-bar"` 区块，其他一行不动」
> 错误：「表格上面那排筛选框里加个字段」

### 9.4 禁止事项（负面清单）

- ✗ 引入任何外部 CDN（字体、图标、CSS 框架）
- ✗ 使用 emoji 作为图标或装饰
- ✗ 硬编码颜色（必须走 CSS 变量）
- ✗ 使用实色边框（`#333` 之类）
- ✗ 除状态语义外使用红/绿色
- ✗ 用 Vue/React 单文件组件形态产出（原型阶段只出可双击的 HTML）
- ✗ 同一屏出现 2 个以上 `.btn-primary`
- ✗ 复制粘贴 HTML 结构（必须抽取 `render*` 函数复用）

### 9.5 代码组织约定

```
<style> 设计令牌 → 基础重置 → 氛围层 → 骨架 → 组件 → 页面专属 → 动效
<script>
  1. Mock 数据（集中定义，标注真源替换点）
  2. 格式化工具（fmtTime / fmtDuration / esc / sparkline）
  3. 渲染片段（render* 纯函数，返回 HTML 字符串）
  4. 统一渲染入口 renderAll()
  5. 交互绑定（bind*）
  6. 初始化
```

所有 HTML 片段必须由 `render*` 函数产出，禁止在事件处理里拼字符串。

---

## 10. 自检清单（每批页面完成后逐条核对）

- [ ] 是否使用了 CSS 变量而非硬编码颜色
- [ ] 数字是否全部等宽 + `tabular-nums`
- [ ] 状态是否同时具备「颜色 + 形态」双重区分
- [ ] 明/暗两种主题是否都检查过
- [ ] 是否覆盖 正常/加载/空/错误 四态
- [ ] 是否标注了 `data-block` 锚点
- [ ] 是否零外部依赖（断网双击可开）
- [ ] 是否包含 `prefers-reduced-motion` 降级
- [ ] 同一屏是否只有 1 个主按钮
- [ ] 高亮发光元素是否 ≤ 3 处
