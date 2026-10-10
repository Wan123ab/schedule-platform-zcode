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
| S2 | 人工干预：stop / retry / rerun-failed / enqueue-front | ✅ 2026-10-11 |
| S3 | 回填：preview 双闸门 / 批次管理 / 滚动投放引擎 | ✅ 本轮 |
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

### 1.3 S3：回填（本轮）

**交付物**：

- `common`：`SequenceOps`（发号函数抽象）—— server（StringRedisTemplate）与 scheduler（Redisson）
  共用 `IdGen`/`flowops:seq:*` key 空间（M-09 序号全平台单调的前置）；`IdGen.incrementRaw` 裸自增
- `domain`：**`TaskCreationService`**（docs/06 §6.2 ① 落库唯一入口，D-12 的"落库"半边下沉 domain
  —— server 手动提交与 scheduler 回填投放必须走同一份建任务+建步骤逻辑，D-08 下 domain 是两者
  共同依赖终点）；`BackfillBatch` 实体 + `BackfillBatchMapper`（pause/resume/reopen CAS +
  `refreshProgress` 幂等结算）+ `BackfillQueryMapper`（冲突检测/已投放日期/状态计数/失败日期）+
  `DateArrayTypeHandler`（PG `date[]` ⇄ `LocalDate[]`）
- `server`：
  - `BackfillService`：**双闸门**（docs/07 §6.5）—— preview 生成 SHA-256（canonical 串含 TreeMap
    排序的 overrides + versionId）存 Redis `flowops:backfill:preview:{hash}`（5 分钟 TTL）；
    submit 服务端**重算比对** + 校验缓存存在性 + 一次性删除（跳过预览/改配置/过期 → 42231）；
    提交**只建批次行**（RUNNING + waiting=total），不建任务；日期切分越窗（< today−180 / > 昨天）
    跳过、超 120 天 42232；eff_conc 收敛公式 = docs/06 §12.2 原文三层（单测 4 例钉死）；
    pause/resume 走 CAS（落空 42238）；retryFailed 为失败日期**新建任务**（O-59）
  - `TaskCreationServiceBridge`：批次行 → `TaskCreationService.Spec` 翻译（版本号回查 versionNo、
    钉 TriggerType=BACKFILL + backfillBatchId）
  - `BackfillController`：9 端点（CONTRACT §8 表）；`POST /backfills` 幂等强制（docs/07 §13 I-02）；
    必审动作 SUBMIT/PAUSE/RESUME/RETRY_BACKFILL_FAILED；读端点 PROJECT DataScope
  - **`FlowopsDataPermissionHandler` 登记 `backfill_batch → project_id`**（docs/07 §5.4
    `/backfills*` → PROJECT；schema 一致性测试自动与 DDL 比对）
- `scheduler`：**`BackfillDispatcher`**（docs/06 §12.1 五步滚动投放，5s 周期）——
  结算进度（全终结 → DONE）→ 只投 RUNNING 批次 → allowed = min(eff_conc − inflight, 单轮上限 10) →
  待投放日期逐个经 ConcurrencyGuard（FORBID 有实例在跑 → 整批本轮停投，下轮再试）+ TaskCreationService
  建任务 → 有投放则重算计数再结算；`SchedulerWiringConfig` 补 Redisson 版 `IdGen`；
  `SchedulerApplication` 修复扫描面（O-62）
- 单测：`BackfillServiceTest` 17 例 + `BackfillDispatcherTest` 11 例（双闸门两分支 / eff_conc 三层 /
  越窗跳过 / CAS 落空 / 滚动闸门与单轮上限 / skipped+已投放不重建 / DONE 停投 / FORBID 停投 /
  单批异常不拖垮 tick）

**DoD 自检**：

| 项 | 结果 |
|---|---|
| 双闸门 | 服务端重算 hash 比对 + Redis 5min TTL + 一次性删除（docs/07 §6.5）✅ |
| 收敛公式 | docs/06 §12.2 原文三层（sequential→1 / FORBID→1 / min(并发,上限)），preview 返回 policyConflict ✅ |
| 冲突检测 | docs/06 §12.3 原文 SQL（±30min、排除 STOPPED、idx_task_conflict）✅ |
| 滚动投放 | docs/06 §12.1 五步；不预建全部任务；PAUSED/CANCELLED/DONE 停投、在途不中断（§12.4）✅ |
| D-12 | 建任务收敛 `TaskCreationService`（ArchUnit 收敛规则同步扩到 domain service 包）✅ |
| 单测 | server 17 + scheduler 11，全绿 ✅ |

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
| **O-55** | 回填批次的队列解析复用 `SubmitQueryMapper.findAnyEnabledQueueIdByProject`（项目绑定队列兜底），不支持显式指定队列 | CONTRACT §8 请求体无 targetQueue 字段；与手动提交的 M1 缺省口径一致（显式 target_queue 随 M3/M4 细化的挂账仍未动） | 若回填要支持指定队列，先回写 CONTRACT §8 补字段 |
| **O-56** | `plannedAt` = 业务日期 **00:00**（系统时区偏移） | docs/06 §12.3 只说"计划投递时刻"，未定义具体时刻；冲突窗口 ±30min 以它为锚。取 00:00 是"业务日期起点"的最自然读法 | 需回写 docs/06 §12.3 明确 plannedAt 口径；若实际投递是"提交时滚动投"，则 plannedAt 语义需改为投放时刻快照 |
| **O-57** | 批次 overrides **原样**作为新任务变量快照的顶层 JSON，不与工作流默认参数在创建期合并 | 变量 6 层解析链在执行期生效（M3 交付），创建期合并会把快照语义变成第二套解析 —— 两套口径迟早漂移；快照应保持"触发时用户给的东西" | 无需动作，留档 |
| **O-58** | overrides 结构 = **全批次统一的 `Map<String,Object>`**，不支持按日期差异化 | docs/CONTRACT 均未定义 per-date 结构（`tasks[]` 里也没有 overrides 槽位）；不 self-invent（O-23/O-42 同一纪律） | 若产品要求 per-date 覆盖，先定 schema 再加字段 |
| **O-59** | **retry-failed 实现口径**：为失败日期**新建任务**（原 FAILED 行保留作审计、供日志追溯），失败计数经"日期最新任务状态"聚合自然并回 waiting；DONE 批次复活为 RUNNING（reopen CAS） | CONTRACT §8 写"失败计数并入 waiting"，未定义实现方式；原地 reset 会丢"曾经失败过"的审计痕迹（fail_reason/日志引用），新建任务两条全保 | 无需动作，留档；前端展示需知道"一个日期可能有两条任务行" |
| **O-60** | **取消批次推迟实现** | PRD §10.11-5 定义了取消（在途任务保持运行），但 CONTRACT §8 无端点、docs/07 §7.3 必审清单**无 CANCEL_BACKFILL 动作码** —— 实现了也没法过审计清单，按"文档不覆盖→不自造"推迟 | 需先在 CONTRACT §8 补端点 + docs/07 §7.3 补动作码，再实现（调度器侧只需停投，语义已就绪） |
| **O-61** | **批次进度/DONE 判定口径**：`countTasksByStatus` 按**每 biz_date 最新一条任务**聚合（row_number），非裸 group by；`refreshProgress` 在 SQL 内落 DONE + finished_at/duration_ms；waiting = total − success − failed − inflight（含未投放日期） | retry-failed 会保留旧 FAILED 行，裸计数会把同一日期新旧任务各算一次（success+failed+inflight 超过 total）；docs/06 §12.1 ⑤"batch.status=DONE"未定义落库方，收敛在幂等结算语句里最不易漏 | 无需动作，留档 |
| **O-62** | **scheduler 启动类扫描面修复**：`SchedulerApplication` 补 `scanBasePackages = {scheduler, config, domain}`；`SchedulerWiringConfig` 补 Redisson 版 `IdGen` bean | 原扫描面只含 `com.flowops.scheduler` —— `SchedulerWiringConfig`（在 `com.flowops.config`）与 domain `@Service/@Component`（`TaskCreationService`/`SecretCryptoService`）**从未被装配**，调度进程启动即缺 bean（CI 全是单测、不起 Spring 上下文，所以一直没暴露）。S3 的投放引擎依赖这批 bean，属修复而非重构 | 部署侧若曾有"调度进程起不来"的观察，根因即此；后续给 scheduler 补一个上下文冒烟测试 |

**沿用 M3 移交的挂账**：O-9（DataScope 全端点越权实跑，需 PG+Redis）、O-15、O-18/O-19、O-30~O-37、O-39~O-42（见 `README-M3.md` §4/§7）。

**M4 范围内待登记**（S4 开工时处理）：`V5` seed 的 5 个告警 eventType 与 CONTRACT §9 列举不一致 → 以 **DDL CHECK + V5 seed** 为准（2026-10-11 勘定）。
