# FlowOps 工程实施 · M1 交付说明（最小调度闭环）

> 依据：docs/09 §2「M1 · 最小调度闭环」交付物清单与 DoD（技术风险最高的里程碑）。
> 本文是 M1 的自检报告：实现清单、DoD 对照、**未验证与偏离项的如实声明**。
> 代码基线：`backend/`（Maven 5 模块）+ `frontend/`（任务详情页已实装）。

---

## 1. 实现清单（对应 docs/09 M1 交付物表）

| 交付物 | 落点 | 状态 |
|---|---|---|
| 调度内核：状态机（任务 9 态 + 步骤 11 态） | `scheduler/state/TaskStateTransitions` · `StepStateTransitions`（声明式事件表，事件枚举唯一真源） | ✅ |
| 调度内核：单线程 tick | `SchedulerEngine`（fixedDelay=1s，异常不中断，>800ms 告警）+ `SchedulerTickPipeline`（§3.2 九阶段的 M1 子集） | ✅ |
| 调度内核：Redis ZSet 就绪队列 | `queue/QueueScore`（(100-p)×2^40+seq）+ `ReadyQueueManager`（两段式 CAS 出队 + failTicks 队头阻塞） | ✅ |
| 调度内核：ConcurrencyGuard | `common/guard`（接口+纯函数判定）+ `domain/guard/DbConcurrencyGuard`（server/scheduler 共享一份，D-12） | ✅ |
| 调度内核：NodeMatcher 三级排序 | `match/NodeMatcher` + `match/ReservedLedger`（D-22 预留账本准入，防峰值滞后超卖） | ✅ |
| 调度内核：DAG 推进 | `dag/DagGraph`（环检测/consumed 恰好一次）+ `DagAdvancer`（纯决策）+ `TaskOutcome`（终结优先级） | ✅ |
| 调度内核：心跳与超时扫描 | `lifecycle/LifecycleScanner`（SCHEDULING 卡住 2min §4.6 / 步骤超时 §8.3）+ `ResourceReleaser`（清算去重） | ✅（心跳判定见偏离项 O-1） |
| 调度内核：选主与恢复 | `core/LeaderElector`（Redisson，10s 抢锁）+ `RecoveryService`（§10.2：队列/锁/账本重建，等领导权后执行） | ✅ |
| 执行器：SSH | `executor-client/SshExecutorClient`（JSch：双泵流式读取/UTF-8/超时断连/exitCode=null 语义） | ✅ |
| 日志链路 | `log/LogIngestService`（缓冲+INCRBY 发号+批量落库+Pub/Sub）→ task_log 分区表 → server `TaskLogWebSocket`（token 鉴权 + offset 重放） | ✅ |
| 最小接口 | `POST /tasks`（幂等必带 D-17）· `GET /tasks/{id}` · `GET /tasks/{id}/steps` · 日志 WebSocket | ✅ |
| 最小前端 | `views/task/TaskDetailView.vue`（步骤列表 + 实时日志窗口 + 3s 轮询终态自停） | ✅ |

## 2. DoD 自检（docs/09 M1 完成定义）

| # | DoD | 状态 | 说明 |
|---|---|---|---|
| 1 | 3 步骤串行工作流 → 派发真实节点 → 执行 → 日志实时 → SUCCESS | ⚠️ 代码就绪，**待端到端首跑** | 全链路代码已通（提交→准入→推进→出队→匹配→SSH→回执→收敛→日志），需要 PG+Redis+2 台真实 Linux 节点（PR-4） |
| 2 | 失败路径按 failureStrategy 处理 | ✅ 单测就绪 / ⚠️ 待首跑 | 重试分支：`retryOrFail` → markRetrying（next_retry_at 落库）→ task_step_retry；耗尽 → FAILED |
| 3 | maxParallelRuns=1 连续提交 → 40901/排队 | ✅ 单测就绪 | `ConcurrencyPolicyEvaluatorTest` 穷举 FORBID/QUEUE/ALLOW 分支；提交响应 deferred=true 表示排队 |
| 4 | kill -9 调度器 → 备 ≤45s 接管，运行中步骤不误判 | ⚠️ 代码就绪，待混沌测试 | 选主 10s 抢锁 + 看门狗；接管后 `RecoveryService.recover()` 重建派生态；需 compose 2 副本环境实测 |
| 5 | 重启恢复：PG 重建队列，状态零丢失 | ✅ 单测就绪 / ⚠️ 待首跑 | `RecoveryServiceTest` 3 用例（三重建口径/锁重建失败不阻断/幂等） |
| 6 | 幂等 tick：重复 tick 不重复派发 | ✅ 单测就绪 | 两段式 claim 的 CAS WHERE（status+token）即防重复派发闸门；管线测试覆盖 CAS 冲突分支 |
| 7 | 单测覆盖：状态机/队列 CAS/节点匹配 ≥70% | ✅ **已实测** | **`mvn test` 85 用例全绿**（2026-10-07 本地工具链 + CI run 5 双重验证）；行覆盖率门禁（JaCoCo）仍待补 |
| — | **CI 全绿** | ✅ | 仓库已迁 GitHub（origin），Actions **run 5 success**（commit 58175ce）；gitee 保留为备份远端同步推送 |

## 3. 测试资产

| 模块 | 测试类 | 覆盖点 |
|---|---|---|
| common | ConcurrencyPolicyEvaluatorTest | 策略分支穷举 |
| domain | DbConcurrencyGuardTest · SecretCryptoServiceTest | 判定链短路/懒查询；GCM 往返/防篡改 |
| scheduler | TaskStateTransitionsTest · StepStateTransitionsTest | 转移逐行 + 非法拒绝（docs/06 §16） |
| scheduler | QueueScoreTest | 优先级/FIFO/区间不越权（§16 边界 2/3） |
| scheduler | DagGraphTest · DagAdvancerTest · TaskOutcomeTest | 线性/菱形/多分支/NOTE/含环拒绝（§16） |
| scheduler | MutexLockManagerTest（Redis 集成，本地自动跳过） | 串行/waiters/唤醒保序/幂等释放（§16） |
| scheduler | ReadyQueueManagerTest（Redis 集成） | 排序/CAS 三分支/队头阻塞（§16 边界 2/3） |
| scheduler | ReservedLedgerTest · NodeMatcherTest | 恰好满足边界（§16 边界 1）/三级排序/亲和回退 |
| scheduler | SchedulerTickPipelineTest | 两 tick 端到端 + 重试/卡住/互斥分支 |
| scheduler | RecoveryServiceTest · LifecycleScannerTest | §10.2 三重建 / §4.6+§8.3 |
| server | ArchitectureTest（ArchUnit） | D-12 防绕过 / 分层 / D-08 |

CI（Redis 服务容器）：`.github/workflows/ci.yml`。
**✅ 已跑通**：仓库迁移 GitHub 后 Actions run 5 success（backend mvn verify 85 用例 + frontend lint/vitest/vue-tsc/build）；gitee 备份远端同步推送。
**经验留档**：本机曾无 JDK21/Maven/Node20，首轮 CI 暴露 20+ 处编译/测试问题（缺依赖、缺 import、lombok @Value 访问器风格、RLock 重入、MP lambda 缓存等）——现已落地本地工具链（tools/ 下 JDK21+Maven3.9.9+Node20），**推送前本地实跑已成为强约束**（docs/10 Review 清单第 8 条精神的落实）。

## 4. 偏离与简化项（如实登记，均注明去处）

| # | 项 | M1 现状 | 去处 |
|---|---|---|---|
| O-1 | 心跳离线判定（§8.1，DoD §20.8-4） | 未实现——SSH 直跑模型无心跳源 | M2 资产域（心跳上报端点 + 45s 判离线 + 恢复窗口） |
| O-2 | 回执丢失按 §4.6 分流（幂等声明） | M1 全部按"可重新下发"回退 | M3 随算子 `idempotent` 声明落地 UNKNOWN 转人工 |
| O-3 | 互斥唤醒"同 tick 交接"（§6.3.1 Lua） | 重排队等价实现（1 tick 延迟，保序） | M4 压测期换 Lua 原子交接 |
| O-4 | 步骤超时终止远端进程 | execute() 超时断连兜底；kill 按 PID 是 E-02 二期 | 二期 Agent 上报 PID |
| O-5 | 步骤 target_queue / OS / 标签约束 | 管线留了 Demand 入参位，M1 传 null | M3/M4 编排域 |
| O-6 | 变量 6 层解析 | start_command 直传（M1 硬编码工作流） | M3 VariableResolveManager |
| O-7 | stop 收敛（STOPPING→终止进程→STOPPED） | 状态机/枚举/前端展示就绪；终止动作依赖 O-4 | M4 人工干预 |
| O-8 | 覆盖率门禁 / p6spy | 未接入 | M1 收尾后补 JaCoCo 与 dev 调试配置 |

## 5. 已知修复记录（本轮 review 捕获，留档防复发）

1. `task_step.mutex_group` 从未写入 → 互斥判定永远空转（定义透传修复 + TaskSubmitService 拷贝）；
2. 日志通道键与业务编号混淆（`SI-` 装的是全局发号序列而非行 id）→ TaskStepVO 显式下发 `rowId`，前端零推导；
3. ArchUnit 按调用点断言 insert 不可靠（继承自 BaseMapper）→ 改"依赖收敛"等价约束；
4. 回退路径漏重新入队（claim 时已 ZREM）→ 扫描器补 enqueue；
5. XML 更新绕过 MetaObjectHandler → 全部 CAS 补 `updated_at=now()`（同时成为卡住扫描锚点）。

## 6. M1 → M2 交接清单

- M2 资产域开工前：PR-4 环境资源（PG/Redis/2 台真实节点）必须到位（docs/09 §1.3 唯一硬阻塞）；
- 首跑顺序建议：CI（mvn verify）→ compose up（含 2 副本 scheduler）→ 种子工作流 E2E → kill -9 混沌测试（DoD 4）；
- 遗留技术债：JaCoCo 覆盖率门禁、p6spy（dev）、Gitee CI 选型（§3）。
