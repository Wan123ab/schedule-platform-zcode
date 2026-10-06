# FlowOps 前后端接口清单（契约草案 V0.1）

> 依据：《智能工作流调度与监控平台原型规格书 V0.2》（SPEC-02）§5 逐页规格 + §6 实体字段字典
> 范围：**19 页**原型所涉全部前后端交互（auth / projects / clusters / nodes / credentials / operators / workflows / triggers / tasks / backfill / alerts / audit / dashboard / **platform**）
> 状态：草案 —— 供前后端评审签认，非最终实现文档。**字段、错误码、鉴权、序列化以 [07-接口实现规范](../docs/07-接口实现规范.md) 为准**（优先级：07 > 本文档）
>
> **v3 回写状态（2026-09-24，对应 `07 §12` 10 项清单）**：①Bearer 认证 ✅ ②字段顺序/可空 ✅ ③6 角色 + DataScope ✅ ④`trace_id` 格式 ✅ ⑤角色表改写 ✅ ⑥权限点指向 `07 §5.2` 47 点 ✅ ⑦任务状态 8→9 ✅ ⑧步骤状态补 code ✅ ⑨错误码指向 `07 §4.2` ✅ ⑩新增 §0.5 幂等 ✅ —— **10 项已全部执行**
> 字段命名：**本文按 camelCase 书写**（与 §6 实体字典一致）；**线协议是 snake_case**，由后端全局序列化配置统一转换（D-05）。
> ⚠️ 文档书写口径 ≠ 线协议口径：`trace_id`、`page_size`、`order_by`、`order_dir` 等已按线协议改为 snake_case，其余字段在文档中仍沿用 camelCase，实现时以「全局 SNAKE_CASE 策略」为准（`07 §3.1`）。

---

## 0. 全局约定

### 0.1 基础

| 项 | 约定 |
| --- | --- |
| Base URL | `/api/v1` |
| 内容类型 | `application/json; charset=utf-8`（文件上传除外） |
| 认证 | **`Authorization: Bearer {token}`**（Sa-Token，`login-type=flowops`；7 天有效）。**原「Cookie Session」已作废**（回写项 ①，见 `07 §12`） |
| 时间格式 | `YYYY-MM-DD HH:mm:ss`（相对时间仅用于"最近心跳/最近更新"展示，接口一律返回绝对时间） |
| ID 格式 | 任务 `TASK-YYYYMMDD-####` · 工作流 `WF-####` · 算子 `OP-####` · 回填批次 `BF-####` · 项目 `PRJ-####` · 集群 `CL-####` · 凭据 `CR-####` · 告警规则 `AR-####` · 审计 `AU-####` |
| 分页 | 请求 `page`（1 起）+ `page_size`（默认 20）；响应 `total` + `page` + `page_size` + `records[]` |
| 排序 | `order_by` + `order_dir(ASC/DESC)`，白名单校验，默认 `created_at DESC` |

### 0.2 统一响应包

```json
{ "code": 0, "message": "ok", "data": { }, "trace_id": "0af7651916cd43dd8448eb211c80319c" }
```

- `code=0` 成功；非 0 见 §12 错误码。`trace_id` 贯穿审计（§6.11）与服务端日志。
- 分页数据 `data = { total, page, page_size, records[] }`。
- **`trace_id` 格式：32 位小写 hex、无连字符**（对齐 W3C Trace Context；回写项 ④，见 `07 §7.1` I-01）。
  旧示例 `8f2c-41ab` 是带连字符的示意值，**已作废**。客户端可经 `X-Trace-Id` 头传入，不合规则时服务端忽略并自行生成。
- 字段顺序：`code` / `message` / `data` / `trace_id`；`data` 为 `null` 时不省略（回写项 ②，见 `07 §3.1`）。

### 0.3 权限模型

> **v3 回写（回写项 ⑤⑥）**：原表只有 4 个角色，与 PRD §11.2 的 **6 角色 + 数据范围**冲突。**以 PRD §11.2 为准**，下表为权威模型。

| 角色 | code | 能力 |
| --- | --- | --- |
| 平台管理员 | `PLATFORM_ADMIN` | 全平台 |
| 运维人员 | `OPS` | 资产 + 任务运维，**被授权集群**范围 |
| 项目管理员 | `PROJECT_ADMIN` | 本项目内全部 |
| 算子维护者 | `OPERATOR_MAINTAINER` | 本人维护的算子 |
| 业务人员 | `BUSINESS` | 本项目内提交/查看本人任务 |
| 只读/审计 | `AUDITOR` | 全平台只读（审计视角） |

**权限模型 = RBAC + DataScope 双要素**（`07 §5.3` D-19）：

- **权限点（Permission）**判「能不能做」：`schedule:{域}:{动作}`，共 **47 个**，完整清单见 **[07-接口实现规范 §5.2](../docs/07-接口实现规范.md)** —— **该表是唯一权威来源**，本文不再举例。
- **数据范围（DataScope）**判「对谁做」：`ALL / AUTHORIZED_CLUSTER(ids) / PROJECT(ids) / SELF_CREATED / SELF / NONE`。
- 同一用户可有多个角色；同一请求的数据范围由各权限点的 scope 收敛。

### 0.4 状态枚举（全局唯一口径，勿新增同义词）

| 枚举 | 值 |
| --- | --- |
| 任务状态（**9**） | `PENDING / SCHEDULING / RUNNING / **STOPPING** / SUCCESS / FAILED / STOPPED / TIMEOUT / PARTIAL`（回写项 ⑦：补 `STOPPING` 中间态） |
| 步骤实例状态（11） | `NOT_STARTED / WAITING_DEPENDENCY / WAITING_RESOURCE / SCHEDULING / RUNNING / SUCCESS / FAILED / RETRYING / SKIPPED / STOPPED / TIMEOUT`（回写项 ⑧：补 `code`；中文仅作展示） |
| 工作流状态 | `DRAFT / PUBLISHED / DISABLED / ARCHIVED` |
| 并发策略 | `FORBID / ALLOW / QUEUE` |
| 失败策略 | `TERMINATE / RETRY` |
| 触发器类型 | `MANUAL / CRON / API / EVENT`（API·EVENT 一期置灰） |
| 凭据类型 | `SSH_KEY / USER_PASSWORD / WINRM / TOKEN` |
| 凭据状态 | 有效 / 即将过期 / 已失效 / 已吊销 |
| 集群状态 | 正常 / 部分异常 / 不可用 / 维护中 |
| 节点在线 | 在线 / 离线 / 未知 |
| 连接方式 | `SSH / WinRM / Agent` |
| 算子类型 | `JAR / PYTHON / SHELL / BAT / EXE / CUSTOM` |
| 回填批次状态 | `RUNNING / PAUSED / DONE / FAILED` |
| 项目状态 | 启用 / 停用（+ 归档，仅列表展示） |

### 0.5 幂等（v3 新增，回写项 ⑩）

| 项 | 约定 |
| --- | --- |
| 请求头 | `Idempotency-Key: {client_uuid}` |
| 服务端 | Redis `idem:{user_id}:{key}` → 响应体快照，**TTL 24h** |
| 命中同 key + 同请求体 | 返回**首次的响应**（含首次 `code`），不重复执行 |
| 命中同 key + 不同请求体 | `40903` |
| 未带 key | 正常执行（一期不强制） |
| **强制端点** | `POST /tasks`、`POST /backfills`、`POST /workflows/{id}/publish`、`POST /operator-versions/{id}/publish` |

> **口径**：**前端必带、后端不强拒**（`07 §7.2`）。后端将来若收紧为全部写操作强制，前端不会大面积 40903。

### 0.6 任务提交的分流语义（v3 新增，对齐 `07 §6.4`）

`POST /tasks` 的并发策略分流：

- `FORBID` 且已有实例在运行 → `40901`（附 `running_task_id`）
- `QUEUE` 且**等待数**（`queue.max_waiting_tasks`）已满 → `40902`
- `QUEUE` 且进入等待 → **`201 + task{PENDING, queue_position: N}`，这是成功不是错误**
  > 旧版曾用 `QUEUED_WAITING` 状态表达排队，该状态**不在 9 态枚举内，已删除**（`07 §6.4`）。
- **互斥锁不在提交路径上**：互斥锁是**步骤级**、在出队后节点匹配成功时才竞争；`40904` 只由**诊断查询**返回。

---

## 1. 认证与会话（login.html）

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 | 权限 |
| --- | --- | --- | --- | --- | --- |
| POST | /auth/login | 登录 | `username`, `password`, `rememberMe` | `user{username,displayName,roles[]}`, `permissions[]` | 公开 |
| POST | /auth/logout | 登出 | — | — | 登录用户 |
| GET | /auth/me | 当前用户与权限 | — | 同 login | 登录用户 |

服务端规则（对应原型三态）：密码连续错误 **5 次锁定 15 分钟**（倒计时 mm:ss 由前端按锁定截止时间渲染）；锁定期间 login 返回 `code=40101` 并附 `lockedUntil`。登录成功写会话；`rememberMe` 延长有效期至 7 天。

---

## 2. 项目空间（project-list.html）

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 | 权限 |
| --- | --- | --- | --- | --- | --- |
| GET | /projects | 分页列表 | `keyword`, `status`, `page`, `page_size` | `project[]`（§6.1 全字段 + 计数列） | 登录 |
| POST | /projects | 新建 | `projectName`, `description`, `availableClusterIds[]`, `availableQueueIds[]`, `maxConcurrentTasks`, `maxWaitingTasks` | `project` | ADMIN |
| PUT | /projects/{projectId} | 编辑 | 同上（部分更新） | `project` | ADMIN |
| PUT | /projects/{projectId}/status | 启用/停用 | `status(启用/停用)` | `project` | ADMIN |
| GET | /projects/{projectId}/members | 成员列表 | — | `member[]{username,displayName,role,joinedAt}` | ADMIN |
| POST | /projects/{projectId}/members | 添加成员 | `username`, `role` | `member` | ADMIN |
| PUT | /projects/{projectId}/members/{username} | 调整角色 | `role` | `member` | ADMIN |
| DELETE | /projects/{projectId}/members/{username} | 移除成员 | — | — | ADMIN |

约束：负责人不可移除（前端禁删 + 后端校验 `code=42201`）；停用前返回影响面统计（工作流/任务/成员数量），由 `GET /projects/{id}/impact` 或停用接口前置校验返回。成员名查重、角色枚举见 §0.3。

---

## 3. 集群与执行节点（cluster-list / cluster-detail / node-detail）

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 | 权限 |
| --- | --- | --- | --- | --- | --- |
| GET | /clusters | 集群列表 | `status`, `keyword` | `cluster[]`（§6.2） | 登录 |
| GET | /clusters/{clusterId} | 集群详情 | — | `cluster` + `nodes[]{executorNodeId,onlineStatus,cpuUsed…}`（§6.3 精简列） | 登录 |
| GET | /clusters/{clusterId}/nodes | 节点分页 | `onlineStatus`, `osType`, `tags[]`, `page` | `executorNode[]` | 登录 |
| GET | /executor-nodes/{executorNodeId} | 节点详情 | — | `executorNode`（§6.3 全字段） | 登录 |
| PUT | /executor-nodes/{executorNodeId}/enabled | 启用/禁用节点 | `enabled` | `executorNode` | OPS |
| GET | /executor-nodes/{executorNodeId}/tasks | 节点历史任务 | `page` | `task[]` 精简 | 登录 |

心跳字段 `lastHeartbeatAt` 由调度器维护，接口只读。`tags[]` 用于标签约束调度（§6.3）。

---

## 4. 凭据管理（credential-list.html）

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 | 权限 |
| --- | --- | --- | --- | --- | --- |
| GET | /credentials | 分页列表 | `credentialType`, `status`, `keyword` | `credential[]`（§6.4；`secretFingerprint` 仅后 4 位） | OPS |
| POST | /credentials | 新建 | `credentialName`, `credentialType`, `username`, `secret`(写-only), `expireAt`, `description` | `credential` | OPS |
| PUT | /credentials/{credentialId} | 编辑 | 同上（`secret` 仅在轮换时传） | `credential` | OPS |
| DELETE | /credentials/{credentialId} | 删除 | — | — | OPS |
| POST | /credentials/{credentialId}/rotate | 轮换密钥 | `secret` | `credential`（更新 `lastRotatedAt`） | OPS |
| GET | /credentials/{credentialId}/references | 引用列表 | — | `ref[]{targetType,targetId,targetName}` | OPS |

约束：`refCount > 0` 禁止删除（`code=42202`，前端置灰删除按钮）；密码类字段**永不回显**。

---

## 5. 算子与版本（operator-list / operator-detail / operator-version）

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 | 权限 |
| --- | --- | --- | --- | --- | --- |
| GET | /operators | 列表 | `operatorType`, `projectId`, `keyword` | `operator[]`（§6.6） | 登录 |
| POST | /operators | 新建 | `operatorName`, `operatorType`, `projectId`, `description` | `operator` | OPS |
| GET | /operators/{operatorId} | 详情 | — | `operator` + `versions[]{versionId,versionNo,publishStatus,isDefaultVersion,…}` | 登录 |
| POST | /operators/{operatorId}/versions | 上传新版本 | multipart: `file`, `description`, `osType`, `startCommand`, `workDir`, `envVars[]`, `paramTemplate[]`, `outputDeclarations[]`, `defaultResource{}` | `operatorVersion`（`fileChecksum` 服务端计算 SHA-256） | OPS |
| GET | /operator-versions/{versionId} | 版本详情 | — | §6.6 版本全字段 | 登录 |
| PUT | /operator-versions/{versionId} | 编辑草稿版本 | 同上传字段（`file` 可省） | `operatorVersion` | OPS |
| POST | /operator-versions/{versionId}/publish | 发布 | — | `operatorVersion`（`publishStatus=published`，设默认版本） | OPS |
| POST | /operator-versions/{versionId}/offline | 下线 | — | `operatorVersion` | OPS |
| GET | /operator-versions/{versionId}/references | 被引用列表 | — | `ref[]{workflowId,workflowName,stepName}` | OPS |

`ParamDef`：`name/key/type(TEXT·NUMBER·BOOLEAN·SINGLE·DATETIME)/required/defaultValue/rule/help/runtimeOverridable`。
`OutputDeclaration`：`varName/extractMode(REGEX·FILE)/expression/valueType/exampleValue/description`。
上传校验失败态：`code=42210` + `errors[]{field,message}`（原型含校验失败演示）。

---

## 6. 工作流 / 版本 / 触发器（workflow-list / workflow-editor / workflow-detail）

### 6.1 工作流

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 | 权限 |
| --- | --- | --- | --- | --- | --- |
| GET | /workflows | 列表 | `projectId`, `status`, `keyword`, `order_by`, `order_dir` | `workflow[]`（§6.7 + `lastRunStatus/lastRunAt`） | 登录 |
| POST | /workflows | 新建 | `workflowName`, `projectId`, `description` | `workflow`（初始 `DRAFT`） | OPS |
| GET | /workflows/{workflowId} | 详情 | — | `workflow` + `currentVersion` 概要 | 登录 |
| PUT | /workflows/{workflowId} | 基础信息 | `workflowName` 等 | `workflow` | OPS |
| PUT | /workflows/{workflowId}/concurrency | 并发设置 | `concurrencyPolicy`, `maxParallelRuns` | `workflow` | OPS |
| POST | /workflows/{workflowId}/publish | 发布版本 | `versionId` | `workflow`（`currentVersion` 切换） | OPS |
| POST | /workflows/{workflowId}/disable | 停用 | — | `workflow`（`DISABLED`） | OPS |

### 6.2 版本与 DAG

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 |
| --- | --- | --- | --- | --- |
| GET | /workflow-versions/{versionId} | 版本全量（含 DAG） | — | §6.7 版本：`dagDefinition{steps[],edges[]}` + `workflowParams[]` + `triggerConfig[]` |
| PUT | /workflow-versions/{versionId} | 保存草稿 | 同上（整包保存） | `versionId` + `hasDraftChanges=true` |
| POST | /workflows/{workflowId}/versions | 基于当前版本新开草稿 | — | `operatorVersion` 结构 |

`WorkflowStep` 字段全量见 §6.7（含 `mutexGroup` 互斥锁组、`tagConstraint[]`、`failureStrategy`）。DAG 整包保存，不做步骤级增量接口。

### 6.3 触发器

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 |
| --- | --- | --- | --- | --- |
| GET | /triggers | 列表（工作流维度聚合） | `workflowId` | `trigger[]`（§6.8） |
| POST | /triggers | 新建 | `triggerName`, `workflowId`, `triggerType`, `cronExpression`, `period`, `effectiveRange{start,end}`, `runParams`, `targetQueueId` | `trigger`（`nextFireTime` 服务端计算） |
| PUT | /triggers/{triggerId} | 编辑/启停 | `enabled` 等 | `trigger` |
| DELETE | /triggers/{triggerId} | 删除 | — | — |
| GET | /triggers/cron-preview | Cron 预览（分钟级） | `cronExpression`, `count(默认5)` | `nextTimes[]{datetime}` |

---

## 7. 任务（task-list / task-detail）

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 | 权限 |
| --- | --- | --- | --- | --- | --- |
| GET | /tasks | 分页列表 | `status`, `workflowId`, `projectId`, `clusterId`, `triggerType`, `bizDateFrom/To`, `keyword`, `page` | `task[]`（§6.9 精简列） | 登录 |
| GET | /tasks/{taskId} | 任务详情 | — | `task` 全字段 + `diagnosisInfo` + `variableSnapshot` | 登录 |
| GET | /tasks/{taskId}/steps | 步骤实例列表 | — | `taskStep[]`（§6.9：`resourceRequest/resourceActual/outputVars/retryHistory[]`） | 登录 |
| GET | /tasks/{taskId}/steps/{stepInstanceId}/logs | 步骤日志 | `offset`, `limit`, `stream(EOF)` | `{content, eof, totalLines}`（日志保留 30 天） | 登录 |
| POST | /tasks | 手工提交任务 | `workflowId`, `versionId?`, `runParams{}`, `targetQueueId?` | `task`（`PENDING`） | DEV+ |
| POST | /tasks/{taskId}/stop | 停止任务 | `stopReason`(≥5 字符，必填) | `task`（`STOPPED`, `stoppedBy`） | OPS |
| POST | /tasks/{taskId}/retry | 重跑（原实例续跑/重投） | — | `task` | OPS |
| POST | /tasks/{taskId}/rerun-failed | 重跑失败步骤 | `stepInstanceIds[]?`（缺省=全部失败步骤） | `task` | OPS |
| GET | /tasks/{taskId}/diagnosis | 调度诊断 | — | `{queuePosition, aheadCount, eta, blockingReasons[], suggestions[]}` | 登录（对外仅 eta+suggestion） |

约束：停止原因写入审计日志（§6.11 `action=STOP_TASK`）；步骤级停止一期不提供（PRD 缺口 #9）。

---

## 8. 回填补数（backfill.html）

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 | 权限 |
| --- | --- | --- | --- | --- | --- |
| POST | /backfills/preview | **生成预览**（不落库） | `workflowId`, `bizFrom`, `bizTo`, `concurrency`, `sequential`, `overrides{}` | `{tasks[]{bizDate,plannedTaskId,plannedAt,conflict{taskId,status}|null}, skippedDates[], plannedCount, conflictCount, effConc}` | OPS |
| POST | /backfills | 提交回填 | 同 preview 请求 + `confirmedPreviewHash`（防跳过预览直提） | `backfillBatch{batchId,total,waiting}` | OPS |
| GET | /backfills | 回填批次列表（进度区） | `status(RUNNING/PAUSED/DONE/FAILED)` | `backfillBatch[]`：`batchId,name,workflowId,bizFrom,bizTo,concurrency,sequential,status,total,success,failed,waiting,submitter,submittedAt,failures[]{date,taskId,reason}` | OPS |
| GET | /backfills/history | 历史归档记录 | `page` | `history[]{batchId,name,workflow…,success,failed,total,duration}` | OPS |
| POST | /backfills/{batchId}/pause | 暂停 | — | `backfillBatch`（`PAUSED`） | OPS |
| POST | /backfills/{batchId}/resume | 继续 | — | `backfillBatch`（`RUNNING`） | OPS |
| POST | /backfills/{batchId}/retry-failed | 重跑失败 | — | `backfillBatch`（失败计数并入 waiting） | OPS |
| GET | /backfills/{batchId}/tasks | 批次任务明细 | `page` | `task[]` | OPS |

关键语义（与页面双闸门一致）：
1. **预览必须先于提交** —— 提交携带 preview 返回的 `confirmedPreviewHash`，服务端校验配置未变更，否则 `code=42231`；
2. **并发冲突提示** —— preview 响应附 `policyConflict{policy,maxParallelRuns,effConc}`，前端渲染 FORBID 收敛提示；服务端按 `effConc = sequential ? 1 : (FORBID ? 1 : min(concurrency, maxParallelRuns))` 执行；
3. **时间冲突标黄** —— `conflict` 判定口径：同工作流 + 同业务日期 + 计划投递时刻落在已有任务 ±30 分钟；
4. 越窗日期（近 180 天之外 / 晚于昨天）在 preview 中以 `skippedDates[]` 返回，不生成任务；
5. 单次回填上限 **120 个业务日期**（超限 `code=42232`）。

---

## 9. 告警中心（alert-config.html）

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 | 权限 |
| --- | --- | --- | --- | --- | --- |
| GET | /alert-rules | 规则列表 | `eventType`, `enabled` | `alertRule[]`（§6.10） | OPS |
| POST | /alert-rules | 新建 | `ruleName`, `eventType(TASK_FAILED/TASK_TIMEOUT/NODE_OFFLINE/QUEUE_BACKLOG/RESOURCE_HIGH)`, `condition(JSON)`, `channels[](EMAIL/WECOM/WEBHOOK)`, `receivers[]`, `suppressWindowMinutes`, `scope{}` | `alertRule` | OPS |
| PUT | /alert-rules/{ruleId} | 编辑 | 同上 | `alertRule` | OPS |
| PUT | /alert-rules/{ruleId}/enabled | 启停 | `enabled` | `alertRule` | OPS |
| DELETE | /alert-rules/{ruleId} | 删除 | — | — | OPS |
| GET | /alert-channels | 通知渠道 | — | `channel[]{type(EMAIL/WECOM/WEBHOOK),endpoint,verified}` | OPS |
| PUT | /alert-channels/{type} | 更新渠道 | `endpoint`, `secret?` | `channel` | OPS |
| POST | /alert-channels/{type}/test | 测试发送 | — | `{ok, latencyMs, error?}` | OPS |
| GET | /alert-history | 触发历史 | `eventType`, `from`, `to`, `page` | `alert[]{ruleId,eventType,targetId,firedAt,channels[],result}` | OPS |

`condition` JSON 按事件类型定义（如 `TASK_FAILED`：`{minRetries, scope}`）；`suppressWindowMinutes` 抑制窗口内同类事件不重复通知。

---

## 10. 审计与追踪（audit-log.html）

| 方法 | 路径 | 说明 | 关键请求 | 关键响应 | 权限 |
| --- | --- | --- | --- | --- | --- |
| GET | /audit-logs | 分页列表 | `from`, `to`(快捷 今天/近7天/近30天 由前端换算), `operator`, `targetType`, `action`, `result`, `trace_id`, `page` | `auditLog[]`（§6.11：含 `beforeSummary/afterSummary/sourceIp`） | ADMIN |
| GET | /audit-logs/{auditId} | 详情（字段级 diff） | — | `auditLog` + `diff[]{field,old,new}` | ADMIN |
| GET | /trace/{trace_id} | 关联追踪链 | — | `auditLog[]`（同 trace_id 按时间升序，跨对象串联） | ADMIN |

`targetType`：PROJECT / WORKFLOW / OPERATOR / CLUSTER / CREDENTIAL / TASK / BACKFILL / ALERT_RULE。对象 ID 可跳转对应详情页（前端职责，字段返回 `targetType+targetId` 即可）。

---

## 11. 工作台聚合（index.html）

| 方法 | 路径 | 说明 | 关键响应 |
| --- | --- | --- | --- |
| GET | /dashboard/summary | 指标卡 | `{taskTotal,runningCount,successRate,queueBacklog,onlineNodes,totalNodes,failedToday,alerts}` |
| GET | /dashboard/task-trend | 近 7 日任务趋势 | `trend[]{date,submitted,success,failed}` |
| GET | /dashboard/resource-usage | 集群资源 | `usage[]{clusterId,cpuUsed,cpuTotal,gpuUsed,gpuTotal,diskUsed,diskTotal}` |
| GET | /dashboard/cluster-health | 集群健康 | `health[]{clusterId,status,nodeOnline,nodeTotal,runningTasks}` |
| GET | /dashboard/queue-backlog | 队列积压 | `backlog[]{queueId,queueName,waitingCount,maxWaiting}` |
| GET | /dashboard/recent-failures | 最近失败任务 | `task[]` 精简（≤10） |
| GET | /dashboard/latest-alerts | 最新告警 | `alert[]`（≤10） |

聚合接口允许一次请求多点渲染；前端 5s 轮询（缺口 #22：真实实现建议列表轮询 5–10s、日志 WebSocket）。

## 11.1 平台健康度（v3 新增第 19 页）

| 方法 | 路径 | 说明 | 关键响应 |
| --- | --- | --- | --- |
| GET | /platform/health | 平台自身健康（PRD §15.5-5） | 见下 |

权限点：`schedule:platform:health`（平台管理员、运维人员）；前端 **10s 轮询**，不走 WebSocket。

```json
{
  "scheduler": { "leader_id": "scheduler-1", "is_leader": true,
                  "lease_remaining_seconds": 22, "last_tick_at": "...", "tick_interval_ms": 1000,
                  "standby_count": 1 },
  "db":     { "pool_active": 6, "pool_idle": 24, "pool_max": 30, "slow_query_count_1m": 0 },
  "redis":  { "connected": true, "used_memory_human": "512M", "eviction_policy": "noeviction", "queue_depth": 42 },
  "nodes":  { "online": 196, "offline": 4, "total": 200, "online_rate": 0.98 },
  "queues": [ { "queue_id": 3, "name": "default", "waiting": 12, "running": 5,
                 "max_concurrent_tasks": 20, "max_waiting_tasks": 200, "water_level": 0.06 } ],
  "today":  { "dispatched_count": 4821, "failed_count": 17, "log_ingest_bytes": 53687091200 },
  "recent_alerts": [ { "alert_id": "AR-0007", "level": "WARN", "title": "...", "fired_at": "..." } ]
}
```

> 详细字段定义与刷新策略见 [`07-接口实现规范 §6.7`](../docs/07-接口实现规范.md)；页面需求见 [`08-非功能与运维方案 §5.4`](../docs/08-非功能与运维方案.md)。

---

## 12. 错误码

> **⚠️ v3 回写（回写项 ⑨）**：下表是原型阶段的**最小集**。**完整错误码表以 [07-接口实现规范 §4.2](../docs/07-接口实现规范.md) 为准**（五段式 `{HTTP语义}0{域}{序号}`，域 0 通用 / 1 资产 / 2 编排 / 3 执行 / 4 治理）。
> 本表已定义的码**不得改动**（原型已联调）；新增码一律按 `07 §4.1` 的编码规则扩展。

| code | 含义 | 场景 |
| --- | --- | --- |
| 0 | 成功 | — |
| 40001 | 参数校验失败 | 附 `errors[]{field,message}` |
| 40100 | 未登录 | 重定向 login |
| 40101 | 账号已锁定 | 附 `lockedUntil`，前端渲染倒计时 |
| 40300 | 无权限 | 前端渲染"无权限"态（如回填页 noperm 视图，缺失 `schedule:backfill:write`） |
| 40400 | 资源不存在 | — |
| 40900 | 状态冲突 | 如重复提交/发布非草稿版本 |
| 42201 | 负责人不可移除 | 项目成员 |
| 42202 | 凭据被引用禁止删除 | `refCount>0` |
| 42210 | 算子上传校验失败 | 附 `errors[]` |
| 42231 | 预览已失效/未预览 | 回填提交闸门 |
| 42232 | 回填范围超限 | >120 个业务日期 |
| 50000 | 服务内部错误 | 附 `trace_id` |
| 50300 | 依赖服务不可用 | 调度器/消息队列失联 |

---

## 13. 前端交互 → 接口对照（关键闸门）

| 页面交互 | 接口支撑 |
| --- | --- |
| 登录 5 次锁定 + 倒计时 | `POST /auth/login` 返回 `40101 + lockedUntil` |
| 回填「必须先生成预览」 | `confirmedPreviewHash` 校验（§8） |
| 回填并发策略冲突提示 | preview 响应 `policyConflict`；FORBID 收敛 `effConc=1` |
| 预览时间冲突标黄 | preview 响应 `conflict{taskId,status}`（±30 分钟窗口） |
| 任务停止（原因≥5字） | `POST /tasks/{id}/stop`，写入审计 |
| 凭据 refCount 禁删 | `DELETE /credentials/{id}` 返回 `42202`；列表预置置灰 |
| 项目停用影响面确认 | 停用前置 impact 统计 |
| 算子发布后可被编排 | `publishStatus=published` 才出现在编辑器算子库 |
| Cron 分钟级预览 | `GET /triggers/cron-preview` |
| 审计字段级 diff（左红右绿） | `GET /audit-logs/{id}` 返回 `diff[]{field,old,new}` |
| trace_id 串联追踪 | `GET /trace/{trace_id}` |

---

*本文档由批次 7 产出，与 18 页原型同源；实体字段以 SPEC-02 §6 为准，后续变更须双向同步。*
