# -*- coding: utf-8 -*-
import io

p = 'pages.html'
s = io.open(p, encoding='utf-8').read()

def rep(old, new, tag):
    global s
    assert old in s, 'ANCHOR MISS: ' + tag
    s = s.replace(old, new, 1)

old = "    ['19', '平台健康度', 'platform-health.html', '§15.5-5（租约/决策表）']\n  ];"
new = """    ['19', '平台健康度', 'platform-health.html', '§15.5-5（租约/决策表）'],
    ['20', 'API 接入', 'api-trigger.html', '§10.9/§17（API 触发提前纳入一期）：Key 鉴权（一次性明文/轮换双活）、开放接口（创建/详情/终止/重试）、调用记录、Webhook 通知（HMAC 签名+重试链路）']
  ];"""
rep(old, new, 'add20')

old = "共 <b style=\"color:var(--pri)\">19 个业务页面</b>"
new = "共 <b style=\"color:var(--pri)\">20 个业务页面</b>"
rep(old, new, 'count')

io.open(p, 'w', encoding='utf-8').write(s)
print('pages OK')

p = 'README.md'
s = io.open(p, encoding='utf-8').read()

old = "基于 `prd/智能工作流调度与监控平台_PRD_V0.2.md`（含 v3 架构评审回写 R-1~R-8）从零设计，供产品评审与需求确认。"
new = """基于 `prd/智能工作流调度与监控平台_PRD_V0.2.md`（含 v3 架构评审回写 R-1~R-8）从零设计，供产品评审与需求确认。

> **V0.2d 变更（API 触发模块）**：新增第 20 页「API 接入」（`api-trigger.html`）——第三方系统经 X-API-Key 鉴权触发/查询/终止/重试任务，配 Webhook 状态通知（HMAC 签名 + 重试链路）。PRD §10.9 的 API 触发由 V1.1 提前纳入一期；整体设计（鉴权流程/接口定义/数据模型/通知协议/回写清单）见 **`DESIGN-api-trigger.md`**。工作流详情的 DAG 编排预览同步升级为**与编辑器同渲染**（foreignObject 节点 + 类型图标 + 四向贝塞尔 + 配置节点虚线注入）。"""
rep(old, new, 'readme-changelog')

old = "| 19 | 平台健康度 | `platform-health.html` | §15.5-5（v3 R-1/R-6）、08 §5.4 |"
new = """| 19 | 平台健康度 | `platform-health.html` | §15.5-5（v3 R-1/R-6）、08 §5.4 |
| 20 | API 接入 | `api-trigger.html` | §10.9/§17（API 触发 · Key 鉴权 · 开放接口 · Webhook 通知）· 设计见 DESIGN-api-trigger.md |"""
rep(old, new, 'readme-row')

io.open(p, 'w', encoding='utf-8').write(s)
print('README OK')
