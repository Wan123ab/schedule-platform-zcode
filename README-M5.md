# FlowOps 工程实施 · M5 交付说明（O-9 越权实跑 + O-18 controller 契约 + O-19/O-85 收尾）

> 本轮不是"新功能"，是**补几笔挂了两三个里程碑的账**：
> `O-9`（DataScope 全端点越权实跑，M2 遗留）、`O-18`（controller/aspect 层的集成测试，M3 遗留），
> 以及依赖 O-9 才做的 `O-19`（common 的 web/exception 门禁）与 `O-85`（scheduler 上下文冒烟）。
> 前三者的共同前提是「起真实 PostgreSQL + Redis + 完整 Spring 上下文」，而这个前提此前从未具备 ——
> 于是**"生产进程能不能起来"在这项目里从未被任何一道关卡验证过**。
>
> 结论：前提一旦具备，第一轮实跑就抓出 **9 个真问题**，其中 **3 个是"启动即死"**、
> **2 个是静默失效**；给 scheduler 补同类测试时又抓出第 4 个"启动即死"（O-86）。
> 它们全部通过了 M4 收官时的 648 个用例。
>
> **✅ 本轮收官（2026-10-25）**：后端 **718 用例**（648 → +70，59/83/14/455/107）/ 6 道覆盖率门禁全绿；
> 前端 **8 文件 128 例**三件套全绿；CI run **#42 / #43 / #45** 双 job success（commit `f573a07` / `41589c7`）。
> CI 日志可核实集成测试是真跑而非跳过 —— 后端 job 里能逐个数到：
> `DataScopeMatrixIT` 41 / `ControllerContractIT` 8 / `ContextStartupIT` 2 / `SchedulerContextIT` 3 /
> `GlobalExceptionHandlerTest` 10 / `TraceIdFilterTest` 6，且 `All coverage checks have been met` 出现 6 次。

---

## 0. 环境：本机 PostgreSQL 16（免安装，不写注册表、不改系统 PATH）

O-9 卡了三个里程碑的唯一原因就是没有 PG。本轮按许可把它装上：

| 项 | 值 |
|---|---|
| 归档 | `https://get.enterprisedb.com/postgresql/postgresql-16.10-1-windows-x64-binaries.zip`（322 MB） |
| 解压位置 | `D:\zcode_projects\tools\pg16\pgsql` |
| 数据目录 | `D:\zcode_projects\tools\pg16\data`（`initdb -U postgres -E UTF8 --locale=C`） |
| 端口 | **55432**（刻意不占 5432：机器上可能另有安装，撞库比连不上更难查） |
| 库/用户 | `flowops_it` / `flowops` |

```bash
# 起（前台，便于看日志；后台起也行）
pg16/pgsql/bin/postgres.exe -D pg16/data -p 55432 -c listen_addresses=127.0.0.1
# 建库一次即可
psql -h 127.0.0.1 -p 55432 -U postgres -c "CREATE ROLE flowops LOGIN PASSWORD 'flowops' SUPERUSER;" \
                                          -c "CREATE DATABASE flowops_it OWNER flowops;"
```

**这个环境不进 CI**：CI 由 `.github/workflows/ci.yml` 的 `postgres:16-alpine` service 提供
（端口映射 `55432:5432`，与本地默认值一致，于是 `IT_DB_PORT` 两边都不用改）。

---

## 1. 切片清单

| 切片 | 内容 | 状态 |
|---|---|---|
| S1 | 集成测试骨架：真实 PG16 + 内嵌容器 + 真实登录（`AbstractIntegrationTest` / `IntegrationEnvironment` / `fixtures.sql`） | ✅ |
| S2 | **O-9**：DataScope 越权矩阵 `DataScopeMatrixIT`（41 例：23 越权 + 10 正例 + 5 列表泄漏 + 1 已知越权面 + …） | ✅ |
| S3 | **O-18**：controller/切面/全局异常层契约 `ControllerContractIT`（8 例） | ✅ |
| S4 | CI 接入：postgres service + 环境变量；surefire 收 `**/*IT.java` | ✅ |
| S5 | **O-19**：`GlobalExceptionHandler` / `TraceIdFilter` 补测 + common 门禁口径扩到 `web`/`exception` | ✅ |
| S6 | **O-85**：scheduler 上下文冒烟 `SchedulerContextIT`（3 例），并抓出 O-86 | ✅ |

### 1.1 为什么用 RANDOM_PORT（真实容器）而不是 MockMvc

先写的是 MockMvc，卡在 `ServerEndpointExporter` —— 它要求 ServletContext 上有
`jakarta.websocket.server.ServerContainer`，MOCK 环境没有，直接 `IllegalStateException` 打挂上下文。
可选做法是 `@MockBean` 把它换成假实现，但那等于"为了跑测试把真实组件摘掉"；
而本轮要回答的第一个问题恰恰是**"生产进程能不能起来"** —— 摘掉组件后得出的"能起来"是假结论。
改跑真端口、真 HTTP，顺带把 WebSocket 端点导出、过滤器链、Sa-Token 拦截器一并纳入验证范围。

代价：单类首次启动约 25~30 s（上下文在测试类间共享，只付一次）。

### 1.2 环境缺失时静默跳过，不误报失败

`IntegrationEnvironment`（JUnit `ExecutionCondition`）在**容器级**求值，早于 Spring 上下文创建。
这一点是关键：写在 `@BeforeAll` 里的 `assumeTrue` 太迟 —— `SpringExtension` 的
`beforeAll` 回调会先把上下文建好，环境缺失会表现为"上下文启动失败"而不是"测试被跳过"。
（scheduler 的 Redis 用例用 `Assumptions` 就够了，因为那里没有 Spring 上下文。）

### 1.3 夹具纪律

`it/fixtures.sql`：**自清理**（`IT-` 业务码打标，先删后插）、**只造最小合法行**（绕过业务校验
是刻意的 —— 让"越权判定"成为唯一变量，不被创建期校验抢先拒绝）、**A/B 两项目严格镜像**
（否则无法区分"越权"与"数据本身有问题"）。
执行走 `ScriptUtils` 而不是 JdbcTemplate：项目铁律是"SQL 与 Java 分离"，
夹具 SQL 应逐行可评审，而不是变成 Java 里的字符串拼接。

---

## 2. DoD 自检

| 项 | 结果 |
|---|---|
| M2 DoD-5「A 项目用户访问 B 项目资源全端点 → 全 40301」 | 23 条越权用例全 40301，并带 `resource_type` ✅ |
| 「区分不存在/越权」（docs/07 §4.2） | 40400 与 40301 已按语义分流；本轮把 5 处误报 40400 的修成 40301（O-77）✅ |
| 列表端点不泄漏 | `total` 与 `records` 同口径（O-75 修复后）✅ |
| 正例对照（别把"全 40301"做成"全查不到"） | 10 条可见性用例 ✅ |
| O-18 的鉴权/参数绑定/错误码映射 | 8 例覆盖 40100 / 40300 / 40001 / 40002 / 40400 / 响应形状 / trace_id ✅ |
| CI 上真跑 | ci.yml 补 postgres service，非"本地绿、CI 跳过" ✅ |

---

## 3. 本轮修复记录（9 个，全部是"第一轮实跑才暴露"的）

### 3-1. 三个"启动即死"——进程从来就没起来过

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| **O-72** | Flyway 迁移即失败：`No value provided for placeholder: ${step.X.output.Y}` | Flyway 默认把 `${...}` 当占位符；而 `${step.X.output.Y}` 是本项目**变量引用的业务语法**（docs/07 §9.1），它出现在 `V1__baseline.sql` 的一行 DDL 注释里 | 主 `application.yml` 加 `spring.flyway.placeholder-replacement: false`（本项目迁移不用 Flyway 占位符） |
| **O-73** | `NoSuchBeanDefinitionException: ConcurrencyGuard` | `DbConcurrencyGuard` 在 domain 且**没有** `@Component`，只有 scheduler 的 `SchedulerWiringConfig` 手工 new 了一个；server 侧从来没有这个 bean | 新增 `config/ServerWiringConfig` 显式装配（不给 domain 加 `@Component`：会与 scheduler 的显式 `@Bean` 撞成 `NoUniqueBean`） |
| **O-74** | `Ambiguous mapping. Cannot map 'platformController' …` | `GET /platform/health` 有两个处理器方法：M0 的骨架（`PlatformController`）与 M4 的真实实现（`PlatformHealthController`） | 删 M0 骨架。它返回的是硬编码假数据，留着只会让"健康页显示 100%"变成没人敢信的数字 |

**为什么 M4 收官时都没发现**：648 个用例全是分层单测，谁都不起 Spring 上下文；
而"映射歧义""缺 bean""迁移脚本语法"这三件事**只**在装配期暴露。

### 3-2. 分页 `total` 绕过数据权限（O-75，信息泄漏 + 功能错乱）

实测：`GET /workflows`（PROJECT 用户）→ `records` 只剩本项目 1 条（正确），但 `total = 2`（含他项目）。

根因：`PaginationInnerInterceptor` 拼 count 用的是它拿到的 `boundSql.getSql()`，
而 count 语句最终由 `executor.query(...)` 直接执行 —— 传给它的 executor 是
`MybatisPlusInterceptor` 包裹**之前**的原始对象，因此 count **不会再走一遍拦截器链**。
于是排在分页之后的拦截器增强对 count 一律不生效。

修法：**把 `DataPermissionInterceptor` 移到 `PaginationInnerInterceptor` 之前**
（MyBatis-Plus 官方文档亦要求"分页插件放最后"）。
修完 5 个列表端点（projects/workflows/tasks/backfills/operators）的 `total` 全部正确。

后果不只是"多显示一个数"：既把"平台上一共有几个你看不到的项目"泄漏了出去，
也让前端分页器显示"共 2 条"而第二页是空的。

### 3-3. 审计静默全失败（O-76）

`AuditLog` 实体声明了 `createdBy`（`@TableField(fill = INSERT)`），
而 `audit_log` 表**根本没有这一列**（docs/05 §3.6 的权威 DDL 与 V3 迁移都没有）。
于是每一次审计落库都在真实 PG 上抛 `column "created_by" of relation "audit_log" does not exist`；
而 `AuditAspect` 是自吞异常的（不能让审计失败拖垮业务），
于是表现为**审计一条都没落进去、控制台只有一行 ERROR**。

修法：删实体字段，以 DDL 为准（`operator` 已记录"谁做的"，`created_by` 在审计语义上冗余）。
防复发：`ControllerContractIT.审计落库` 直接查库断言 `audit_log` 有行 ——
这类"静默失效"只有落到库里才算数，断言"方法被调用了"没有意义。

### 3-4. 五处把"越权"报成"不存在"（O-77）

| 位置 | 修前 | 修后 |
|---|---|---|
| `ProjectService.requireByBusinessId` | 40400 | 40301 |
| `TaskQueryService.requireTask` | 40400 | 40301 |
| `TaskDiagnosisService.requireTask` | 40400 | 40301 |
| `TaskInterventionService.requireTask` | 40400 | 40301 |
| `BackfillService.requireBatch` | 40400 | 40301 |

`TaskQueryService` 里原本还写着一行注释"本端点暂不区分，登记为 README-M4 偏离" ——
而 **README-M4 里其实没有这条登记**（查证过：M4 偏离表只有 O-70 涉及 DataScope，讲的是 dashboard 聚合口径）。
于是这条注释成了一个自我实现的豁免：说要登记、实际没登记、也就没人回来修。
**教训：注释里写"登记为 X"不算登记，必须真的落到偏离表里有编号。**

判据（docs/07 §4.2 + ScopeGuard 的立论）：运维拿到一个任务编号却看到"任务不存在"，
第一反应是去查数据是不是被清了；而真实原因往往只是这个任务不属于他的项目/集群。

### 3-5. 两处错误码映射（O-78 / O-81）

| # | 场景 | 修前 | 修后 |
|---|---|---|---|
| **O-78** | 请求体缺失 / 不是合法 JSON | 50000 | 40001（`GlobalExceptionHandler` 加 `HttpMessageNotReadableException`） |
| **O-81** | 未登录 / token 过期 | 50000 | 40100（新增 `config/SaTokenExceptionAdvice`） |

O-81 的后果比看起来严重：前端对 40100 的处理是"清会话、跳登录页"，
而 50000 的语义是"服务端坏了"。于是 token 过期后用户看到的是"系统内部错误"，
既不知道要重登、也无法自行恢复（刷新重试都还是 500），同时每一条都在日志里留下 ERROR 级堆栈。

它为什么漏：`SaInterceptor` 抛的是 Sa-Token 自家的 `NotLoginException`，
而 `GlobalExceptionHandler` 在 `flowops-common` —— common **不依赖 Sa-Token**
（它还要被 scheduler 用），不可能在那里 catch。**适配器放在用 Sa-Token 的那一侧**（server），
common 保持与会话框架无关。

### 3-6. 一个测试夹具的线程安全（O-82，不是被测代码的 bug）

`SshExecutorClientTest` 的收集器用 `ArrayList`，而 stdout / stderr **各有一个泵线程**
同时回调 `lines.add(...)` —— `size++` 互相覆盖，表现为"随机少一行"。
后果是全量构建偶尔红、单独重跑又绿（2026-10-11 首次观察到，本轮再次复现）。
改成 `CopyOnWriteArrayList` 根治。

这条值得单独记一笔：它**不是**被测代码的问题，但代价是"整个 `verify` 随机失败"，
会持续侵蚀对 CI 结果的信任 —— 看到红的第一反应变成"再跑一次"而不是"真有 bug"。

### 3-7. scheduler 上下文冒烟抓出的第四个"启动即死"（O-86）

`SchedulerContextIT` 第一次跑就红：

```
RedisConnectionException: Unable to connect to Redis server: 127.0.0.1:6379
  ← ERR Client sent AUTH, but no password is set
```

根因是 **`password: ${REDIS_PASSWORD:}` 这个"看起来无害"的默认值**：环境变量缺失时它解析成
**空串**，而 Redisson 分不清"没配密码"和"配了空串" —— 空串照样触发 `AUTH`，
对着一个根本没设密码的实例发 `AUTH ""`，服务端直接拒。
`IdGen` / `MutexLockManager` / `LeaderElector` 全要 `RedissonClient`，于是**调度进程起不来**。

| 为什么此前没人发现 | 说明 |
|---|---|
| docs/08 §7 把 `REDIS_PASSWORD` 列为**必填** | 部署环境一定会设，所以生产路径从来不触发 |
| 但本地与 CI 跑的就是无密码实例 | 那是合法场景 —— 于是"能起进程"这件事在本地/CI 永远没被验证过 |
| server 侧不受影响 | server **没有** Redisson（只有 `StringRedisTemplate`），Lettuce 对空串的处理不同 |

修法放在配置层而不是 yml：yml 里没法表达"缺省则整键不存在"（`${X:}` 给空串、`${X:#{null}}`
在 `@ConfigurationProperties` 绑定下不会被求值成 null，只会变成字面量 `#{null}`），
而 `RedissonAutoConfigurationCustomizer` 正是 Redisson 官方留的、在 `RedissonClient` 建连前
改 `Config` 的扩展点 —— 空白密码一律置 null。

两个踩坑点留档：

1. `Config.getSingleServerConfig()` 是 **protected**，外部只能配 `isSingleConfig()` + `useSingleServer()`；
   而 `useSingleServer()` 在别的模式已配置时会**抛异常**，所以必须先 `is*Config()` 判再用。
2. `RedissonAutoConfigurationV2` 只是 `extends RedissonAutoConfiguration`（`javap` 看它自己一个方法都没有），
   所以 V1 里对 customizer 的处理照样生效 —— 只看 V2 的字节码会误判"不支持 customizer"。

---

## 4. 偏离与遗留项（如实登记，O-72 起）

| 编号 | 项 | 现状 | 去处 |
|---|---|---|---|
| **O-72** | Flyway 占位符与业务变量引用语法冲突 | 已闭环（`placeholder-replacement: false`） | — |
| **O-73** | server 缺 `ConcurrencyGuard` bean | 已闭环（`ServerWiringConfig`） | — |
| **O-74** | `GET /platform/health` 双映射 | 已闭环（删 M0 骨架） | — |
| **O-75** | 分页 count 绕过 DataScope | 已闭环（拦截器顺序） | — |
| **O-76** | `AuditLog.createdBy` 列不存在 → 审计静默全失败 | 已闭环（删实体字段） | — |
| **O-77** | 5 处越权误报 40400 | 已闭环（接 ScopeGuard） | — |
| **O-78** | 请求体缺失 → 50000 | 已闭环（新增 handler） | — |
| **O-79** | **凭据未纳入 DataScope 登记制 —— PROJECT 用户可读他项目凭据** | **未修**。`credential` 表不在 `FlowopsDataPermissionHandler` 的两张登记表里，`CredentialService` 也没有任何范围判定；而 docs/07 §5.4 给凭据写的是 "AUTHORIZED_CLUSTER / PROJECT"，即应当按项目过滤 | 修之前必须先定：**平台级凭据（`project_id IS NULL`）谁可见？** 一旦登记 PROJECT 维度，`project_id IS NULL` 的平台级凭据会对所有 PROJECT 用户消失，而执行节点绑定的很可能正是平台级凭据 —— 那会让"节点详情看不到凭据"变成新的故障。`DataScopeMatrixIT.已知越权面_凭据未纳入数据范围登记制` 断言当前行为（200），一旦有人改了登记制该断言立刻变红，提示回来重评 |
| **O-80** | **没有 Swagger / springdoc，O-18 提到的"Swagger 契约"无可测物** | 全仓 0 处 `io.swagger` 注解、0 个相关依赖；docs/07 §5.4 的示例里写的 `@Operation` 从未落地 | 若要 API 文档：先引入 springdoc-openapi，再给端点补注解，然后才能谈"契约测试" |
| **O-81** | 未登录 → 50000 | 已闭环（`SaTokenExceptionAdvice`） | — |
| **O-82** | `SshExecutorClientTest` 夹具非线程安全 | 已闭环（`CopyOnWriteArrayList`） | — |
| **O-83** | PROJECT 用户可见全部 `cluster` / `queue` / `executor_node` | 未修。docs/07 §5.4 给这三个端点写的数据范围是 **ALL**（"运维可见被授权集群"），且 `cluster`/`queue`/`executor_node` 只在 **AUTHORIZED_CLUSTER** 维度登记；PROJECT 范围不注入，故全可见。与 O-70（dashboard 的 cluster/queue 聚合按全局口径）是同一条判断 | 若要求 PROJECT 用户看不到全局容量，需先定义脱敏口径（只出使用率不出绝对值），再回头改登记 |
| **O-84** | **审计查询端点未实现** | docs/07 §5.4 列了 `/audit-logs*` 与 `/trace/{trace_id}`，但全仓没有 AuditController —— 审计**能落库**（本轮已验证）却**查不出来** | 属功能缺口：需先定列表的筛选维度与 `/trace/` 的聚合口径，再实现 |
| **O-85** | **scheduler 仍没有上下文冒烟测试** | **已闭环**：新增 `SchedulerContextIT`（3 例：14 个关键 bean 装配 + 空密码不鉴权 + 配置绑定）。它连的是已被 server IT 迁移过的 `flowops_it`，并在**类加载期**（早于 Spring 上下文、早于 `@BeforeAll`）清空业务表 —— 否则 `RecoveryService`(ApplicationRunner) 与每秒一次的 tick 会把夹具里的 PENDING 任务真的推进、真的去连 `10.0.0.1` 建 SSH。迁移脚本只在 server 模块，scheduler classpath 上没有，故不开 Flyway 建独立库（依赖 reactor 顺序：server 先跑） | — |
| **O-86** | **空 Redis 密码让 scheduler 起不来** | **已闭环**（见 §3-7）：`${REDIS_PASSWORD:}` 的空串被 Redisson 当成"要鉴权" | — |
| **O-19** | controller 之外的 `GlobalExceptionHandler` / `TraceIdFilter` 无门禁 | **已闭环**：补 `GlobalExceptionHandlerTest`(10) + `TraceIdFilterTest`(6)，common 的 JaCoCo `includes` 从 `util/guard/context` 扩到 `web/exception`，阈值仍 0.85（实测 99.1%）。按项目纪律做了**反向扰动**（阈值临时改 0.999 → `Rule violated` + BUILD FAILURE）确认它会拦 | — |

**沿用 M4 移交的其余挂账**：O-15、O-30~~O-37、O-39~~O-71（见 `README-M3.md` §4 与 `README-M4.md` §4）。

**覆盖面如实说明**：O-9 的原文口径是"全端点实跑"。本轮覆盖的是
**带资源编号、且落在 DataScope 登记表上**的端点（约 35 个路径，分布于项目/工作流/版本/算子/任务/回填/触发器/集群/节点/队列），
不是全部 88 个端点。未覆盖的主要是：纯创建端点（无资源编号可越权）、
内中心跳 `/internal/**`、以及凭据域（见 O-79）。

---

## 5. 数字基线

| 项 | M4 收官 | 本轮 |
|---|---|---|
| 后端用例 | 648 | **718**（common **59** / domain 83 / executor-client 14 / **server 455** / scheduler **107**） |
| 新增 | — | +70：server 侧 +51（`ContextStartupIT` 2 + `DataScopeMatrixIT` 41 + `ControllerContractIT` 8）、common +16（`GlobalExceptionHandlerTest` 10 + `TraceIdFilterTest` 6）、scheduler +3（`SchedulerContextIT`） |
| 覆盖率门禁 | 6 道全绿 | 6 道全绿（common 口径扩到 `web`/`exception`，实测 99.1%） |
| 前端 | 8 文件 128 例 | 8 文件 128 例（lint 0 警告 / vitest / vue-tsc / vite build 全绿） |
| CI | 只有 Redis | Redis + **PostgreSQL 16** |

> 本机 `pnpm build` 需要 `vite build --emptyOutDir=false`：沙箱拦截了 `wmic.exe`
> （`node-safe-delete` 用它做删除前探测），`emptyDir` 阶段会失败。
> 这是**本机环境限制**，与代码无关；CI（ubuntu runner）不受影响。已如实记录以免后来人误判为构建坏了。
