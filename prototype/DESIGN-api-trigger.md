# 「API 触发」模块设计方案（开放接口 · V1.1 提前纳入一期）

> 状态：设计定稿待评审 ｜ 上游：PRD §10.9（API 触发，原列 V1.1）、§17（系统集成）、§22-7 ｜ 原型：`api-trigger.html`（第 20 页）
> **范围演进声明**：PRD V0.2 将 API 触发列在 V1.1（§22-7 结论"MVP 不支持"）。本方案按业务方决策将其**提前纳入一期**交付，需回写 PRD §10.9 / §18.4 与 §22-7 结论。

---

## 1. 定位与设计原则

第三方系统（风控中台、数据平台、IM 机器人、上游调度系统）不走页面、直接以接口方式：**创建任务（触发）、查询详情、终止、重试**，并通过 **Webhook 实时接收状态变更**。

| # | 原则 | 说明 |
|---|---|---|
| P-1 | **复用优先，不旁路** | API 创建的任务与页面触发**完全同构**（trigger_type='API' 枚举已预留）：必须经过 ConcurrencyGuard（D-12 统一入口）、幂等键、限流、审计——绝不允许开放接口绕过页面侧的任何闸门 |
| P-2 | **机器身份与人的身份分离** | 机器走 API Key（X-API-Key），不走 Sa-Token 会话；两套鉴权平行，审计中可区分"人操作"与"系统调用" |
| P-3 | **最小暴露** | 开放接口只暴露 状态/步骤/进度/失败原因摘要；运行参数值、日志内容、变量快照**不对第三方开放**（作用域之外的数据零暴露） |
| P-4 | **安全默认** | Key 明文仅创建时展示一次；作用域白名单默认为空（需显式授权）；限流默认 60 次/分 |

## 2. 鉴权设计

### 2.1 API Key 模型

| 项 | 设计 |
|---|---|
| 格式 | `fk_live_` + 32 字节随机（43 字符 base64url）；明文**仅创建时展示一次** |
| 存储 | 服务端只存 **SHA-256 哈希**（key_hash），掩码列（`fk_****8c3f`）用于界面识别；丢失只能轮换不可找回 |
| 作用域 | `project_id`（必须）+ `workflow_scope`（工作流白名单数组，`*`=全部；空=仅查询）——越权返回 40111，不泄露资源存在性 |
| 限流 | 令牌桶按 Key 独立计数（默认 60 次/分，Redis `INCR+EXPIRE` 实现）；超限 42901 + `X-RateLimit-Reset` |
| IP 白名单 | 可选绑定出口 IP 段，未命中 40112 |
| 轮换 | 新旧 Key **双活 24h**（第三平滑切换），到期旧 Key 自动失效；轮换写审计 |
| 过期 | 可设 expire_at；调用时校验 |

### 2.2 鉴权流程

```
第三方系统                          FlowOps
    │  POST /openapi/v1/tasks            │
    │  Header: X-API-Key / Idempotency-Key │
    │─────────────────────────────────────►│
    │                                ① SHA-256(key) 比对 key_hash（无效/停用/过期 → 40110）
    │                                ② IP 白名单（未命中 → 40112）
    │                                ③ 限流令牌桶（超限 → 42901 + retry_after）
    │                                ④ 作用域校验（workflow_id ∈ 白名单 → 否则 40111）
    │                                ⑤ 幂等检查（Idempotency-Key，24h 命中返回首次响应）
    │                                ⑥ ConcurrencyGuard.checkBeforeSubmit（40901/40902）
    │                                ⑦ 创建任务（trigger_type='API'，submitter='API · {key名称}'）
    │                                ⑧ 写 OPENAPI_CALL 审计 + openapi_call_log
    │◄── 201 { task_id, status, links } ───│
```

## 3. 接口定义（/openapi/v1，独立于登录态 /api/v1）

统一响应包/错误码/时间格式/snake_case 全部沿用 `07-接口实现规范`。新增错误码：**40110**（Key 无效/停用/过期）、**40111**（作用域越界）、**40112**（IP 不在白名单）、**42901**（限流，附 retry_after）。

| 端点 | 方法 | 说明 | 关键校验 |
|---|---|---|---|
| `/openapi/v1/tasks` | POST | **创建任务（触发）** | Idempotency-Key **强制**；workflow_id ∈ 作用域；params 按算子模板校验；version 可锁 `v2` 或 `latest`；并发 40901/40902 照常返回 |
| `/openapi/v1/tasks/{task_id}` | GET | 任务详情（状态/步骤/进度/失败摘要 + links） | task_id ∈ 作用域；40400 不泄露存在性 |
| `/openapi/v1/tasks` | GET | 列表查询（status/workflow/分页） | 自动按 Key 作用域过滤 |
| `/openapi/v1/tasks/{task_id}/stop` | POST | 终止 | reason 必填 ≥5 字符；非终态校验 42233；语义与页面停止一致（STOPPING→STOPPED） |
| `/openapi/v1/tasks/{task_id}/retry` | POST | 重试 | `mode=failed_steps`（默认，复用上游产出）/ `all`（新任务实例）；仅终态 42235 |

**协议细节**：限流响应头 `X-RateLimit-Limit / Remaining / Reset`；所有响应带 `trace_id`（第三方报障凭此关联审计与调用记录）。

## 4. 数据模型（对齐 05 风格：公共六列、CHECK、部分索引）

```sql
CREATE TABLE api_key (
    id bigserial PRIMARY KEY,
    key_id varchar(32) NOT NULL,              -- AK-0001
    key_name varchar(128) NOT NULL,
    key_hash varchar(64) NOT NULL,            -- SHA-256，明文不落库
    key_mask varchar(32) NOT NULL,            -- fk_****8c3f
    project_id bigint NOT NULL REFERENCES project(id),
    workflow_scope text[] NOT NULL DEFAULT '{}',   -- '{*}' = 全部；空 = 仅查询
    rate_limit_per_min integer NOT NULL DEFAULT 60,
    ip_whitelist text[] NOT NULL DEFAULT '{}',
    status varchar(16) NOT NULL DEFAULT 'ENABLED'
               CHECK (status IN ('ENABLED','DISABLED','REVOKED')),
    expire_at timestamptz,
    created_by varchar(64),
    last_used_at timestamptz,
    rotate_of bigint REFERENCES api_key(id),  -- 轮换链
    created_at/updated_at/version/deleted ... -- 公共六列
);
CREATE UNIQUE INDEX uk_api_key_hash ON api_key(key_hash) WHERE deleted = false;

CREATE TABLE openapi_call_log (              -- 与审计 OPENAPI_CALL 同源双写
    id bigserial PRIMARY KEY,
    key_id bigint NOT NULL,
    endpoint varchar(128) NOT NULL,
    task_id bigint,                           -- 创建成功才非空
    http_code integer NOT NULL,
    latency_ms integer,
    trace_id varchar(32) NOT NULL,
    note text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_calllog_key_time ON openapi_call_log(key_id, created_at DESC);  -- 保留 90 天

CREATE TABLE webhook_subscription (
    id bigserial PRIMARY KEY,
    sub_id varchar(32) NOT NULL,              -- WH-0001
    name varchar(128) NOT NULL,
    url text NOT NULL,
    events text[] NOT NULL,                   -- {task.succeeded, task.failed, ...}
    secret_hash varchar(64) NOT NULL,         -- whsec_ 的哈希（HMAC 验签密钥）
    project_id bigint NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ENABLED',
    created_by varchar(64), ...
);

CREATE TABLE webhook_delivery (
    id bigserial PRIMARY KEY,
    sub_id bigint NOT NULL,
    event varchar(32) NOT NULL,
    task_id bigint,
    http_code integer,
    attempt integer NOT NULL DEFAULT 1,       -- 重试链路：1/2/3
    result varchar(16) NOT NULL DEFAULT 'SENT'
               CHECK (result IN ('SENT','RETRY','FAILED')),
    latency_ms integer,
    created_at timestamptz NOT NULL DEFAULT now()
);
```

**trigger 表联动**：API 触发不需要 cron 行——`trigger_type='API'` 直接落在 task 上；工作流详情的触发器页签展示"API 触发卡片"（绑定 Key 数、调用量，入口到 API 接入页）。

## 5. 通知机制（Webhook 推送）

### 5.1 事件目录

| 事件 | 触发点 | 一期默认 |
|---|---|---|
| `task.succeeded` / `task.failed` / `task.timeout` / `task.stopped` | 任务终态收敛时 | ✓ 推送 |
| `task.retry_exhausted` | 重试耗尽（42236 同源） | ✓ |
| `step.started` / `step.finished` | 步骤级进度（可选订阅） | 订阅时勾选 |
| `concurrency.rejected` | 40901 触发被跳过 | ✓ |

### 5.2 推送协议

```http
POST {subscription.url}
X-FlowOps-Event: task.failed
X-FlowOps-Delivery: dlv_7f2a91c4        ← 幂等键（第三方据此去重）
X-FlowOps-Signature: sha256=9f31c0…     ← HMAC-SHA256(body, whsec_)
Content-Type: application/json

{ "event": "task.failed", "task_id": "TASK-20260925-0035",
  "workflow_id": "WF-0001", "status": "FAILED",
  "fail_reason": "步骤「模型评分」失败：GPU OOM（exit 137）",
  "finished_at": "2026-09-25 01:12:39",
  "links": { "detail": "/openapi/v1/tasks/TASK-20260925-0035" } }
```

- **验签**：第三方用订阅时下发的 `whsec_` 对 body 做 HMAC-SHA256 比对签名头——防伪造、防重放（附时间戳）
- **重试**：非 2xx 响应按 **5s / 30s / 300s** 三次退避重试，仍失败标记 FAILED 并告警 Key 负责人；送达全链路（含每次尝试）落 `webhook_delivery`
- **与平台告警的关系（§10.12）**：告警面向**平台用户**（邮件/企微），Webhook 面向**第三方系统**——通道独立、抑制策略独立、互不干扰
- **轮询兜底**：第三方也可纯轮询 GET 详情（不做 Webhook）；两种方式不互斥

## 6. 与现有机制的关系（复用清单）

| 机制 | 复用方式 |
|---|---|
| ConcurrencyGuard（D-12） | API 创建走同一 `createTask` 入口，FORBID/QUEUE 策略与页面一致 |
| 幂等（D-17） | 开放接口**强制** Idempotency-Key（页面仅建议）——重试风暴风险更高 |
| 审计（55 动作） | 新增 `OPENAPI_KEY_CREATE/DISABLE/ROTATE/DELETE` + `OPENAPI_CALL`（调用级）|
| 限流 | 新组件 `OpenApiRateLimiter`（Redis 令牌桶），与登录态限流独立 |
| trigger_type | 枚举已含 `'API'`（05 §3.5 / mock 枚举一致），任务列表/详情/告警零改造 |
| 停止/重跑 | 复用 42233/42235 语义与 STOPPING 状态机，reason 写审计 |
| 调度诊断/变量快照 | API 创建的任务同样有完整诊断面板与快照（页面侧可见 submitter='API · xxx'）|

## 7. 原型说明（api-trigger.html · 第 20 页）

- **入口**：侧栏 系统管理 → API 接入（新权限点 `schedule:openapi:read/write`，演示角色：平台管理员/项目管理员可管理）
- **四个页签**：API Keys（列表/创建/禁用/轮换/删除）｜接入文档（curl 示例 + 错误码 + 限流说明）｜调用记录（含 40110/40901 等真实错误样本）｜Webhook 通知（订阅卡片 + 含重试链路的送达记录）
- **关键演示点**：创建 Key 一次性明文展示；调用记录含失败样本（40901 并发拒绝、40110 停用 Key）；送达记录含 502→重试→成功链路
- **联动**：工作流详情触发器页签新增「API 触发」卡片（绑定 Key 数/今日调用/入口）；任务列表新增 API 触发示例任务（TASK-20260925-0110）

## 8. 待确认事项

| # | 事项 | 建议默认 |
|---|---|---|
| O-11 | HMAC 签名是否强制（第三方必须验签） | 一期下发 whsec_ 但仅提示验签，V1.1 强制 |
| O-12 | 限流默认值（60 次/分/Key） | 沿用，可在 Key 级覆盖 |
| O-13 | Webhook payload 是否含变量快照全文 | 不含（P-3 最小暴露）；仅 task_id + 回查链接 |
| O-14 | 是否支持批量创建（一次接口创建多任务） | 一期不支持；回填场景走页面 |

## 9. PRD / docs 回写清单

| 位置 | 回写内容 |
|---|---|
| PRD §10.9 | 触发类型表：API 触发 V1.1 → **一期**；补鉴权/Webhook 说明 |
| PRD §17 / §22-7 | 系统集成表同步；§22-7 结论更新 |
| PRD §18.4 | V1.1 清单移除 API 触发（已提前） |
| docs/07 §5.2 | 权限点 +2：`schedule:openapi:read/write`（47 → 49）|
| docs/05 | 新增 4 表 DDL（api_key / openapi_call_log / webhook_subscription / webhook_delivery）|
| docs/07 §4.2 | 错误码 +4：40110/40111/40112/42901 |
| CONTRACT / SPEC | 新增 /openapi/v1 章节与第 20 页 |
