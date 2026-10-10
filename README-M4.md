# FlowOps 工程实施 · M4 交付说明（执行域完善 + 回填 + 告警）

> 沿用 M2/M3 的文档结构：每切片一节，DoD 自检 + 偏离登记（O-xx 续编，M3 止于 O-42）。
> M4 范围：任务读侧（S1a）→ 调度诊断（S1b）→ 人工干预（S2）→ 回填（S3）→ 告警（S4）→ 工作台（S5）→ 前端 5 页（S6）。
> 库表 / 权限点 / 错误码 / 告警事件种子均已就绪（`V1`~`V5`），M4 是纯 service/controller + 前端里程碑。

---

## 1. 切片清单

| 切片 | 内容 | 状态 |
|---|---|---|
| S1a | 任务域读侧：列表 / 详情 / 步骤 / 日志（`TaskQueryService` + 4 端点，`TaskQueryServiceTest` 20 例） | ✅ 2026-10-11（commit `b85e7a7`，CI run #31 双 job success） |
| S1b | 调度诊断 `GET /tasks/{id}/diagnosis`（排队位置 / 前方数量 / ETA / 阻塞原因 / 建议） | ✅ 2026-10-11（`f413fc4`，CI run #32 双 job success） |
| S2 | 人工干预：stop / retry / rerun-failed / enqueue-front | ✅ 本轮 |
| S3 | 回填：preview 双闸门 / 批次管理 | 未开始 |
| S4 | 告警：规则 CRUD + 渠道 + 发送引擎 + 抑制窗口 | 未开始 |
| S5 | 工作台 7 聚合端点 | 未开始 |
| S6 | 前端 5 页（任务列表/详情、回填、告警配置、工作台） | 未开始 |

### 1.1 S1b：调度诊断（2026-10-11，`f413fc4`）

**交付物**：

- `domain`：`DiagnosisQueryMapper`(+XML) —— 队列近 7 天中位排队时长 + 样本数（一条 SQL 同时取，样本数是 docs/00 E-05 闸门的输入，不是可选项）
- `server` DTO：`TaskDiagnosisVO` / `DiagnosisEtaVO` / `DiagnosisBlockReasonVO` / `DiagnosisSuggestionVO`
- `server` 服务：`TaskDiagnosisService`
  - **排队位置**：`PENDING` 且 `queue_id`/`enqueue_seq` 可用时一次 count 推导；
    排序口径 = **优先级降序 + enqueue_seq 升序**，与调度器 `QueueScore` 的出队顺序一致
    （诊断报的位置必须就是调度器眼里的位置，两套口径迟早出事故）
  - **ETA**（docs/06 §5.5）：`中位排队时长 ×（队列位置 ÷ 平均并发度）`；
    样本 < 20 → `estimatedSeconds=null` + `basis="数据不足，无法估算"`；
    结果缓存 Redis `flowops:eta:{queueId}`，TTL 10 分钟（cache-aside 等价于文档的"每 10 分钟刷新"）；
    Redis 故障全程降级（读失败→直查 DB，写失败→只影响下次命中），诊断不因缓存抖动 500
  - **阻塞原因**：`WAITING_RESOURCE` ⟺ 等互斥锁（docs/06 §6.1），读步骤状态 + Redis
    holder/waiters（key 字面量与 scheduler `MutexLockManager` 一致，D-08 不能引用其常量 →
    由单测 `mutexKeys_arePinnedToSchedulerContract` 钉死字面量防漂移）；
    排队等待出 `CONCURRENCY_WAIT`
  - **建议**：文案级（无动作码，见 O-45）
- `TaskController` 补端点：`GET /tasks/{taskId}/diagnosis`（`schedule:task:read` + DataScope，行级过滤由
  `FlowopsDataPermissionHandler` 在 SQL 层生效）
- 单测：`TaskDiagnosisServiceTest` 15 例

**DoD 自检**：

| 项 | 结果 |
|---|---|
| 契约形状 | `queuePosition` / `aheadCount` / `eta` / `blockingReasons[]` / `suggestions[]`（CONTRACT §7）✅ |
| ETA 公式与样本闸门 | docs/06 §5.5 原样实现；样本 < 20 显示"数据不足"（docs/00 E-05）✅ |
| ETA 缓存 | key `flowops:eta:{queueId}`、10 分钟 TTL（docs/06 §5.5）✅ |
| 互斥阻塞结构 | docs/07 §6.6 的 MUTEX_GROUP 形态（部分字段不可得，见 O-44）✅ |
| `40904` 不在本端点 | docs/07 §6.6：仅留给 dry-run 的"现在能否立即执行"判定 ✅ |
| 单测 | 15 例，守四条契约（排队口径/Redis key 字面量/ETA 闸门与降级/终态零查询）✅ |

### 1.2 S2：人工干预（本轮）

**交付物**：

- `TaskInterventionService`（`modules/task/service`）+ `TaskController` 4 端点：
  - `POST /tasks/{id}/stop`：`task:stop` + `@Audited(STOP_TASK)`。**只写 STOPPING 中间态**（docs/06 §15.1 ①，
    新增 XML CAS `TaskMapper#requestStop`：RUNNING → STOPPING + stopped_by/stop_reason 一次完成）；
    STOPPED 由调度器收敛 —— 响应状态因此是 STOPPING（O-49）
  - `POST /tasks/{id}/retry`：`task:retry` + `@Audited(RETRY_TASK)`。**新建任务实例**（docs/06 §9.3），
    走 `TaskSubmitService.create`（D-12 唯一入口，ArchitectureTest 收敛 TaskMapper 访问）；
    绑定**原冻结版本**（D-11）、参数取原任务变量快照、bizDate/queue/priority 沿用（O-51）
  - `POST /tasks/{id}/rerun-failed`：`task:retry` + `@Audited(RERUN_FAILED_STEPS)`。原实例续跑：
    targets = 指定实例或缺省全部 FAILED/TIMEOUT + **下游闭包**（workflow_edge BFS，下游 SUCCESS 也重跑）；
    多列 reset 走 XML `TaskStepMapper#resetForRerun`（retry_count 保留累加、日志引用保留）；
    任务 CAS 回 RUNNING（`TaskMapper#resumeAfterRerun`，带 from 状态），调度器 DagAdvancer 按
    NOT_STARTED 入度分流自然接管
  - `POST /tasks/{id}/enqueue-front`：`task:enqueue_front` + `@Audited(ENQUEUE_FRONT)`。三道闸门：
    仅 PENDING（42237）→ 无互斥组步骤（42200 + `MUTEX_GROUP_NO_PRIORITY`，docs/07 §6.6）→
    队列 `allow_jump_queue`（42200 + `QUEUE_JUMP_FORBIDDEN`，O-50）；效果 = priority 置 100（O-52）
- 单测：`TaskInterventionServiceTest` 16 例（停止只到 STOPPING / CAS 落空拒绝 / 重跑绑定冻结版本 /
  下游闭包不误伤 / 插队三闸门顺序）

**实现取舍（记档）**：干预落库一律**类型化 XML CAS**（`requestStop`/`resetForRerun`/`resumeAfterRerun`/
`jumpQueue`），不用 MyBatis-Plus lambdaUpdate 拼多列 SET —— 一是 docs/10 的"SQL 与 Java 分离"本就要求，
二是实验发现 update wrapper 的 WHERE 参数注册行为不稳定（参数出现在 SQL 段但 `getParamNameValuePairs`
只含 SET 值，单测无从钉住 WHERE 条件），XML 的 WHERE 逐字可评审、可测。

---

## 4. 偏离与遗留项（如实登记，均注明去处）

| 编号 | 偏离 | 理由 | 去处 |
|---|---|---|---|
| **O-43** | **NO_MATCHING_NODE / constraints[] / nearMiss[] 未实现**（docs/06 §5.4 的核心结构） | nearMiss 需要"余量 = 总量 − 预留"，预留账本在**调度器内存**（`ReservedLedger`，重启由 DB 重建但运行期不落库——`task_step` 无 node_id，派发前无从落）；D-08 下 server 不可达。且调度器目前**不写** `task_step.block_reason`（grep 证实）。自建一套 DB 版近失算法（只看总量不看预留）会产出"此节点可跑"的**错误结论**——比没有诊断更糟 | 移交：调度器在节点匹配失败时把 docs/06 §5.4 结构写入 `task_step.block_reason` / `task.diagnosis_info`（列已建好，正是为这个），诊断端点透传。VO 不预造空壳字段 |
| **O-44** | MUTEX_GROUP 缺 docs/07 §6.6 的 `holder_task_id` / `holding_since` / `holding_seconds` | holder value 只存描述串 `"task:TASK-x/step:y"`（docs/06 §6.3 / `MutexLockManager`），无结构化字段、无时间戳；`waiters_count` 可从 waiters ZSet 基数取到（已出） | 随调度器切片把 holder value 改为 JSON（含 taskStepId + 获取时刻）；诊断端点按新结构补字段 |
| **O-45** | suggestions[] 只有文案、无 `action`/`target` | docs/06 §5.4 定义的三个动作码（REDUCE_CPU / CHANGE_QUEUE / CONTACT_OPS）**全部隶属 NO_MATCHING_NODE 场景**（O-43 不可达）；对排队/互斥等待给动作码 = 凭空造码（O-23/O-42 同一纪律）。互斥组明确禁止插队（docs/07 §6.6 的 42200），文案已如实说明 | 随 O-43 一起：调度器写阻塞原因后，按 docs §5.4 的动作码生成建议 |
| **O-46** | 排队阻塞的类型名 `CONCURRENCY_WAIT` 为自定 | docs 只定义了"排队语义 = PENDING + queue_position"（docs/07 §6.4 v3 修订），未给类型名；且排队可能源于项目/工作流/队列**任一级**并发闸门（docs/06 §6.1），用中性名、不自造更细的分型 | 需回写 docs/07 §6.6 补类型枚举；若产品要求区分来源层级，需先定"排队时记录来源闸门"的字段 |
| **O-47** | ETA 统计 SQL 在 docs/06 §5.5 原文基础上**补了 `deleted = false`** | docs 原文漏了逻辑删除条件；统计口径与其余查询一致（不统计已删行）。另：`percentile_cont` 结果经 `EXTRACT(EPOCH)` 化为秒 | 无需动作，留档防"实现与 docs SQL 不一致"的误报 |
| **O-48** | "平均并发度"取 `queue.max_concurrent_tasks`，NULL（不限）按 1（串行）计 | docs/06 §5.5 未定义该分母的取数口径；队列并发上限是出队闸门，即实际并行度上界，取它最接近"平均并发度"且零成本（一次主键查）。替代方案（7 天内逐时刻并发数平均）需要扫全量任务起止区间，成本远超估算精度收益 | 需回写 docs/06 §5.5 明确分母口径；若要求真实平均并发，需另立统计表 |
| **O-49** | **stop 落地/响应状态为 STOPPING**（CONTRACT §7 写 `STOPPED`）；且仅 RUNNING 可停止（PENDING/SCHEDULING 取消 → 42233） | docs/06 §15.1（M-08）：进程还在跑，server 直接写 STOPPED 是错的——调度器终止全部运行中步骤后才收敛 STOPPED。PRD §10.10 只定义"停止**运行中**任务"，排队任务取消未定义，不 self-invent | 需回写 CONTRACT §7 的响应状态；排队任务取消若产品需要，先定语义（直接 STOPPED or 复用 STOPPING）再加转移行 |
| **O-50** | 插队的"队列允许"闸门复用 `42200 + rule=QUEUE_JUMP_FORBIDDEN`（PRD §12.2-6 有闸门、docs 无专用错误码） | 错误码清单（docs/07 §4）没有"队列不允许插队"项；按"不凭空造码"纪律复用 42200 + rule | 需回写 docs/07 §4 增补错误码，或明确该场景用 42200 |
| **O-51** | retry 新实例：`trigger_type=MANUAL`（CHECK 无 RETRY 值）；`biz_date`/`queue_id`/`priority` 沿用原任务 | docs/06 §2.1：整任务重跑 = 新建任务实例，未定义新实例的 trigger 类型；MANUAL 是事实（人工点击触发）。沿用原队列：若原队列已被禁用，新任务会绑到禁用队列（与手动提交到禁用队列同一暴露面，M1 既有行为） | 需回写 docs/06 §9.3 补新实例字段口径；队列禁用场景若要兜底换队，需先定语义 |
| **O-52** | **插队实现 = `priority` 置 100（E-07 上限），不直接写调度器 ZSet** | docs/06 §4.6 写"插队 → ZADD 更新 score"，但 PENDING 任务**不在**任何 ZSet 中（就绪 ZSet 是步骤级、WAITING_RESOURCE 之后才有；`flowops:queue:ready:{queueId}`），server 无法也不应代写 scheduler 的 ZSet（D-08）。priority 落 DB 后，调度器步骤入队时按 `task.priority` 构造 score，效果等价 | 无需动作，留档解释"实现与 docs 字面不一致"的原因 |
| **O-53** | `42236 RETRY_EXHAUSTED` 本切片未使用；rerun-failed 不设次数闸门 | 42236 语义是"重试次数已达上限"——属步骤级**自动**重试耗尽（调度器侧 §9.4 MAX_RETRY=10）。rerun-failed 是人工显式操作，`retry_count` 保留累加（§9.3 ③），再设闸门会让"重跑失败步骤"无法自救 | 42236 留给调度器重试链路；若要求人工重跑也受 MAX_RETRY 约束，需先回写 docs |
| **O-54** | **CONTRACT §7"重跑（原实例续跑/重投）"与 docs/06 §9.3"新任务实例"冲突 → 按 docs/06 拆成两条**：`retry`=新实例、`rerun-failed`=原实例续跑 | docs/06 是调度引擎权威（状态机 RERUN_SOURCES 也按此实现：FAILED/TIMEOUT/PARTIAL → RUNNING 仅 rerun-failed 一条路）；CONTRACT 的合并写法无法同时满足两条转移行 | 需回写 CONTRACT §7 措辞 |

**沿用 M3 移交的挂账**：O-9（DataScope 全端点越权实跑，需 PG+Redis）、O-15、O-18/O-19、O-30~O-37、O-39~O-42（见 `README-M3.md` §4/§7）。

**M4 范围内待登记**（S4 开工时处理）：`V5` seed 的 5 个告警 eventType 与 CONTRACT §9 列举不一致 → 以 **DDL CHECK + V5 seed** 为准（2026-10-11 勘定）。
