# FlowOps 工程实施 · M3 交付说明（编排域）

> 依据：`docs/09` §M3「编排域」交付物清单与 DoD。
> **状态：进行中 —— 本文件当前覆盖「第一切片：算子域」**，随 M3 推进逐节更新。
> 代码基线：`dev_workbuddy` 分支。
>
> 阅读顺序建议：§1 看本切片做了什么 → **§2 看哪些是真验证过的** → §4/§5 看已知缺口与本轮踩到的坑。
>
> **先回答 M2 交接的两个收尾项**：`O-14`（JaCoCo 覆盖率门禁）**已闭环**（见 §5-1~§5-3）；`O-9`（DataScope 全端点越权实跑）**仍未做**，仍挂在 §4。

---

## 1. 本切片实现清单（算子域，对应 `docs/09` §M3 第 1 项）

| 交付物 | 落点 | 状态 |
|---|---|---|
| 四张表实体（`operator` / `operator_version` / `operator_param_def` / `operator_output_decl`） | `domain/entity/asset/Operator*` | ✅ |
| 类型处理器：`Integer[] ⇄ integer[]` | `domain/mybatis/IntegerArrayTypeHandler` | ✅ |
| Mapper + XML（`countVersionReferences` / `findReferences` / `selectMaxVersionIndex` / `softDelete*` / `clearDefaultFlag`） | `domain/mapper/asset/Operator*Mapper(+XML)` | ✅ |
| 算子 CRUD（分页/详情/新建/编辑/删除） | `modules/asset/service/OperatorService` · `controller/OperatorController` | ✅ |
| **42211 删除闸门**：任一版本被工作流步骤引用即禁删 | `OperatorMapper#countVersionReferences`（`count(DISTINCT ws.id)`） | ✅ |
| 版本上传：`multipart` + 服务端 SHA-256 + 内容寻址落盘 | `OperatorVersionService#upload` · `OperatorFileStorage` | ✅ |
| **42210 + `errors[]` 一次性回填**（文件与 meta 错误合并） | `OperatorFileStorage#validate` + `OperatorVersionValidator` | ✅ |
| 参数模板 / 输出声明（整包替换 + 字段路径定位） | `OperatorVersionValidator`（`paramTemplate[2].paramKey` 级定位） | ✅ |
| 发布 / 下线（DRAFT→PUBLISHED→OFFLINE 状态机 + 默认版本） | `OperatorVersionService#publish/offline` | ✅ |
| 引用查询（`GET /operator-versions/{id}/references`） | `OperatorMapper#findReferences` | ✅ |
| 接口：`/operators`、`/operators/{id}/versions`、`/operator-versions/{id}` | `controller/OperatorController` · `OperatorVersionController` | ✅ |
| 审计动作 CREATE/UPLOAD/PUBLISH/OFFLINE/DELETE_OPERATOR | `@Audited`（`docs/07` §7.3 清单逐项对齐） | ✅ |
| 权限点 `schedule:operator:read/write/delete/publish` | `@RequiresPermission`（49 点中已有的 4 个，未新增） | ✅ |
| 上传体积上限（默认 500MB） | `application.yml` `spring.servlet.multipart.*` | ✅ |
| 单测 | 4 个测试类 64 例（见 §3） | ✅ |
| **前端算子三页（列表/详情/版本）** | —— | ⏳ 未开工 |
| **算子试运行 / 工作流 CRUD / 画布编辑器 / DAG 8 规则 / 六层变量解析 / CRON 触发器** | —— | ⏳ 未开工（见 §7） |

**本切片新增文件（后端）**：`domain` 侧 4 实体 + 1 type handler + 4 Mapper(+4 XML) + `OperatorReferenceRow`；`server` 侧 6 DTO + 2 Converter + 1 Validator + 3 Service(`OperatorService`/`OperatorVersionService`/`OperatorFileStorage`) + 2 Controller；`common` 侧 `FieldError`。

## 2. DoD 自检（`docs/09` §M3 相关项）

| # | DoD | 状态 | 说明 |
|---|---|---|---|
| 1 | 算子 CRUD + 版本上传 + 参数模板 + 输出声明 + 发布/下线 + 引用查询 | ✅ **已实测** | 全部接口实装，`OperatorServiceTest`(13) + `OperatorVersionServiceTest`(19) 覆盖状态机与全部闸门 |
| 2 | 上传校验失败返回 **42210 + `errors[]`**（逐字段回填） | ✅ **已实测** | `OperatorVersionServiceTest#上传_文件与meta错误合并为一个42210_且不落盘` 断言 4 条错误同时在列且字段路径可定位；并断言**校验阶段零磁盘写入** |
| 3 | 版本不可变（非草稿不可编辑 → 42212） | ✅ **已实测** | 状态机三段均有断言：非草稿编辑 42212、非草稿发布 42212、非 PUBLISHED 下线 40900 |
| 4 | 引用闸门（42211） | ✅ **已实测** | `OperatorServiceTest#删除_版本已被工作流引用_42211且不落删`，双向断言（`never()` 校验不落删） |
| 5 | **算子试运行**（选节点 + 实时日志 + 退出码，`DRYRUN_OPERATOR`） | ⏳ 未开工 | §7-1 |
| 6 | **工作流 CRUD + 版本化** | ⏳ 未开工 | §7-2 |
| 7 | **自研 SVG 画布编辑器**（D-14） | ⏳ 未开工 | §7-3 |
| 8 | **DAG 校验 8 条规则**（含规则 7 的 42218） | ⏳ 未开工 | §7-4 |
| 9 | **六层变量覆盖链解析器** | ⏳ 未开工 | §7-5 |
| 10 | **触发器 CRON**（42216） | ⏳ 未开工 | §7-6 |
| 11 | **前端 6 页**（算子 3 + 工作流 3） | ⏳ 未开工 | §7-7 |
| — | 单测覆盖门禁（O-14） | ✅ **已实测** | 4 个模块 5 道门禁全绿，且经**反向扰动验证会拦**（见 §5-3） |
| — | **CI 全绿** | ✅ **已实测**（含一次未复现的不稳定，见 §5-6） | **run #14（`dev_workbuddy` @ `3104871`）conclusion = success**；`run #12`（`ddadde7`）同样 success，两个 job 的每一步 `conclusion=success`（GitHub API 自查）。**`run #13`（`686d7bb`，仅改 Markdown）后端 job 失败** —— 与 #12 是同一份后端代码，判定为环境相关不稳定；提交面已补 surefire 上传以便下次定位（§5-6 / O-22） |

## 3. 测试资产与覆盖率基线

后端 `mvn -o clean verify` **268 用例全绿**（common 27 / domain 21 / server 132 / scheduler 88），5 个模块 + 5 道 JaCoCo 门禁 `BUILD SUCCESS`。

| 模块 | 行覆盖 | 分支覆盖 | 门禁 | 门槛 |
|---|---|---|---|---|
| `flowops-common` | util+guard+context 三包 **100%** | — | `jacoco-check`（按 `includes` 收窄） | 0.85 |
| `flowops-domain` | **93.5%** | 91.7% | `jacoco-check` | 0.55 |
| `flowops-server` | 逻辑层(service/scope/manager) **68.7%** | — | `jacoco-check-logic-layer` | 0.55 |
| `flowops-server` | 模块整体 **51.0%** | 47.1% | `jacoco-check-module-floor` | 0.30 |
| `flowops-scheduler` | **80.8%** | **71.2%** | `jacoco-check` | 0.75 / 0.65 |
| `flowops-executor-client` | **无数据**（零测试 → 无 `jacoco.exec` → report 跳过） | — | 无 | 见 **O-17** |

**本切片新增类的覆盖率**（门禁口径之外，单独列出以便审阅）：

| 类 | 行覆盖 |
|---|---|
| `OperatorVersionValidator` | 65/70 = **92.9%** |
| `OperatorService` | 89/96 = **92.7%** |
| `OperatorFileStorage` | 40/41 = **97.6%** |
| `OperatorVersionService` | 210/245 = **85.7%** |
| `OperatorController` / `OperatorVersionController` | 0%（见 **O-18**） |

| 测试类 | 例数 | 覆盖点 |
|---|---|---|
| `OperatorVersionValidatorTest` | 11 | 一次收集全部错误、字段路径可定位（`paramTemplate[2].paramKey`）、重复 key 精确到行、`SINGLE` 必须有候选值 |
| `OperatorFileStorageTest` | 12 | 扩展名白名单/大小上限/空文件；**校验阶段零磁盘写入**；内容寻址（同内容复用同一份文件）；不同算子分目录；落盘失败报 50000 而非 42210 |
| `OperatorServiceTest` | 13 | 编号 `OP-####` 与 `ENABLED` 缺省、重名拒绝、项目不可变更、**42211 双向断言**、删除顺序（先版本后算子，`inOrder`）、40301/40400、项目过滤翻译、快照单点维护 |
| `OperatorVersionServiceTest` | 19 | 状态机三段、42210 错误聚合、meta 非法 JSON 并入 42210（非 40002）、checksum 去重提示、**版本号含软删行续号（不复用）**、子表先删后插且挂版本行主键、`seq` 显式优先/缺省补号、发布先清旧默认（`inOrder`）、下线清默认标记、**版本可见性借父算子判定** |

前端：`npm run lint`（`--max-warnings 0`）· `npm run test`（3 文件 19 例）· `npm run build`（`vue-tsc --noEmit` + vite build）三件套本地全绿。

## 4. 偏离与遗留项（如实登记，均注明去处）

| # | 项 | 现状 | 去处 |
|---|---|---|---|
| **O-9** | DataScope 全端点越权实跑（M2 遗留） | 仍未做（分层单测已就绪） | 同上，需 PG+Redis 环境 |
| **O-17** | `flowops-executor-client` **零测试、无覆盖率数据** | 模块含 `SshExecutorClient`（176 行真实 SSH 逻辑：连接/流泵/T超时/退出码判定），且已被 `ExecutorNodeService` 的连通性测试与 scheduler 接线**实际使用**。因无测试 → 无 `jacoco.exec` → `report` 直接 skip（日志 `Skipping JaCoCo execution due to missing execution data file`）→ **门禁连装都装不上** | 补 `SshExecutorClientTest`（Mockito 代理 JSch 的 `Session`/`ChannelExec` + 假 `LineListener`，断言成功码匹配、超时、流泵、`terminate` 幂等），随后模块才能加门禁。建议随「算子试运行」（§7-1）一并做——那正是它第一次被高频使用的地方 |
| **O-18** | 门禁的"逻辑层"口径**不含 controller/aspect/ws** | `OperatorController`/`OperatorVersionController` 覆盖率 0%。这是**刻意的**：对 controller 写单测只能得到"调一遍方法、断言返回对象非空"的假覆盖率，真正要验的是鉴权/参数绑定/错误码映射/Swagger 契约，那是集成测试的活 | 随 O-9 的 `@SpringBootTest` 一起补（同一个环境前提） |
| **O-19** | `flowops-common` 的 `api`/`enums`/`exception`/`web` 四包无门禁 | 该模块整体 10.8%，但未覆盖部分主要是枚举常量、`ErrorCode`、`ApiResult` 这类"常量 + 几行 getter"；给它们设行覆盖下限只会逼人写凑数测试。其中 `GlobalExceptionHandler`(0/26)、`TraceIdFilter`(0/16) 是**真有逻辑**的 | 门禁已按 `includes` 收窄到 `util`/`guard`/`context` 三包（100%）。前两者需 MockMvc，随 O-9 补 |
| **O-20** | M3 剩余 6 大块（试运行/工作流/画布/DAG 规则/变量解析/CRON + 前端 6 页） | 未开工 | §7 给出建议顺序 |
| **O-21** | `countVersionReferences` 依赖 `workflow_step` / `workflow_version` 表 | 这两张表 M3 尚未建实体，SQL 直查表名。**若表名/列名在建模时变动，这条 SQL 会静默查不到引用（返回 0）→ 42211 闸门失效** | M3-2（工作流 CRUD）落地后，用一条 `@SpringBootTest` 引用闸门正例把它钉住；在那之前，改动这两张表必须回头改 `OperatorMapper.xml` |
| **O-22** | CI `run #13` 后端失败的**具体用例未知** | 同一份代码在 #12/#14 均绿，本地重复 3 轮（含真实 Redis 的互斥锁集成测试）未复现 → 偶发；详情不可得（无 admin 权限取日志、仓库无产物） | 已补"失败时上传 surefire 报告"（§5-6）。下次再红则直接下报告定位；届时优先看 `MutexLockManagerTest` / `ReadyQueueManagerTest` / `HeartbeatScannerTest` / `LifecycleScannerTest` |

## 5. 本轮修复记录（留档防复发）

### 5-1. 覆盖率门禁**抓到了我自己新写的代码**（门禁第一次真实生效）

补完门禁后跑全量，`flowops-domain` 直接挂：

```
[WARNING] Rule violated for bundle flowops-domain: lines covered ratio is 0.46, but expected minimum is 0.55
[ERROR] Failed to execute goal org.jacoco:jacoco-maven-plugin:0.8.12:check (jacoco-check) on project flowops-domain
```

把 CSV 拉出来对比就一目了然：M2 基线 61.4% = `43/(43+27)`，而本轮我的新代码让分母多了 22 行 —— 新增的 `IntegerArrayTypeHandler` **一行测试都没有**。

处理：给三个 type handler 补齐单测（`IntegerArrayTypeHandlerTest` 5 例 / `StringArrayTypeHandlerTest` 4 例 / `JsonbTypeHandlerTest` 4 例），模块升到 **93.5%**。
*说明*：后两个 handler 是 M2 就存在的同类欠账（同样 0 覆盖），本次一并补上——它们是"静默错映射"的典型位置（读成 `"{0,1}"` 字符串或 null 都不会抛异常，直到调度器判错成败时才暴露，而那时现场离成因已经很远）。

### 5-2. `**/*ConverterImpl` 的 exclude **一直没生效**（M2 配置缺陷，本轮发现）

M2 把 `excludes` 写在父 POM 的 `pluginManagement` 里，本意是"MapStruct 生成物不计入覆盖率分母"。实际报告里却**赫然列着 8 个 `*ConverterImpl`，全部 0% 覆盖**（`flowops-server` 分析到 64 个类）。

根因：同一份 `<excludes>` 被喂给**两个不同的匹配域**——
- `prepare-agent` 按 **VM 类名**匹配：`com/flowops/.../OperatorConverterImpl`（无 `.class` 后缀）
- `report` 按 **class 文件路径**匹配：`com/flowops/.../OperatorConverterImpl.class`

`**/*ConverterImpl` 只能命中前者。证据是 `prepare-agent` 的 argLine 里确实带着这三个 pattern（说明配置**传达到了**插件），而 `report` 依然把它们算进分母 —— 所以问题不在"配置没继承"，在**模式本身**。

修复：两个 pattern 各补一个尾 `*`（`**/*ConverterImpl*` / `**/*MapperImpl*`），一份配置同时覆盖两种形态。修完 `Analyzed bundle 'flowops-server'` 从 **64 → 56** 个类，CSV 里 `ConverterImpl` 计数为 **0**。
*教训*：JaCoCo 的 `excludes` 是"一份配置、两个语义"，只测其中一个 goal 的生效情况会得出错误结论（argLine 里有 pattern ≠ report 里也生效）。

### 5-3. 门禁的**反向验证**（证明它真的会拦，而不是 POM 里的摆设）

对新增的 `flowops-common` 门禁做了扰动验证：

| 扰动 | 结果 |
|---|---|
| `minimum` 0.85 → 0.99 | ❌ **没能证明**：三个包实测 100%，任何 ≤1.0 的阈值都不会触发 —— 这个"负面测试"本身设计错了 |
| 往 `includes` 里临时加 `**/api/*`（0 覆盖） | ✅ `Rule violated ... lines covered ratio is 0.39, but expected minimum is 0.85` → **BUILD FAILURE** |

两次都留档的价值在于：**不是所有"把阈值调高却不失败"都等于门禁失效**，也可能是被测对象已经满分。第二行才是能证伪的扰动方式。
（`flowops-domain` 那道门禁本轮已由 §5-1 的真实失败自证，无需重复。）

### 5-4. `IntegerArrayTypeHandler` 的 javadoc 把**读侧语义写在了写侧**

原文写"null 数组按空数组写回"，但写侧走的是 MyBatis `BaseTypeHandler.setParameter`：null 会执行 `ps.setNull(i, Types.ARRAY)`，**不会**变成空数组（读侧 `toIntegers` 才是返回空数组的那一侧）。
实际不会出事，是因为 MyBatis-Plus 的字段策略是 NOT_NULL——字段为 null 时整列不进 SQL，由 DDL 的 `DEFAULT '{0}'` 兜底。
处理：**只改注释不改代码**。给这个不可达路径写"为通过覆盖率门禁而存在"的 override 是另一种反模式；把注释放到准确的层次（读侧=空数组 / 写侧=别传 null，要表达"空"就传 `new Integer[0]`）才是真实收益。

### 5-5. 两条测试夹具错误（全量跑才暴露）

- `OperatorFileStorageTest` 用 `job.zip.exe` 当"非法扩展名"，但白名单**只认末位扩展名**，`exe` 在白名单里 → 断言失败。改用 `job.tar.gz`，并把"只认末位扩展名"这一性质写进注释（一期不做内容嗅探，`docs/08` §15.3-5）。
- `OperatorServiceTest` 只桩了 `projectMapper.selectOne`（入参解析用），漏了 `selectById`（出参补齐用）→ VO 里项目字段静默为 null。抽成 `givenProjectResolvable()` 一并桩。
*教训*：**"入参解析"与"出参补齐"走的是两条不同的查询路径**，只桩其一会得到"接口能跑但字段为空"的假绿。

### 5-6. CI run #13 后端 job 失败：**未复现的环境不稳定**（并顺带修掉"红了也查不到原因"）

现象：`run #13`（`686d7bb`）后端 job 的 `Build & test (5 modules)` 失败，而**该提交相对 `run #12`（`ddadde7`，success）只多了一次 Markdown 编辑** —— 后端构建内容完全相同。同一份代码既绿又红，只能是环境相关的不稳定。

排查动作与结论：

| 动作 | 结果 |
|---|---|
| 取失败详情（GitHub job logs API） | ❌ `403 Must have admin rights to Repository`；仓库也未上传任何产物 → **知道红了，不知道为什么红** |
| 判定"是否本地被跳过的测试在 CI 才跑" | 否。`MutexLockManagerTest`（真实 Redis，`Assumptions` 可跳过）在本地**实际执行了** 6 例 0 跳过（本机 6379 有 Redis） |
| 本地重复压调度模块（时间/Redis 敏感）3 轮 × 88 例 | ❌ 未复现 |
| 重跑同一份代码（`run #14`，`3104871`） | ✅ success → 判定为**偶发**，非确定性失败 |

处理：**没有改任何测试去"洗绿"** —— 未复现的不稳定最忌凭猜测改断言（会把真实缺陷一起改掉）。改为**提升可诊断性**：CI 后端 job 增加 `if: failure()` 时上传 `surefire-reports`（`.github/workflows/ci.yml`），下次再红可直接下载报告定位到具体用例，而不是靠重跑猜。

嫌疑范围（下次优先看这几处，均依赖真实 Redis 或真实时间）：`MutexLockManagerTest`、`ReadyQueueManagerTest`、`HeartbeatScannerTest`、`LifecycleScannerTest`。

*教训*：CI 失败时"拿到失败详情"的能力，应该和"跑测试"一起建设。这次靠重跑才绕过去，下次不能指望同样的运气。

## 6. 复跑命令（本地）

```bash
# 后端：全量测试 + 5 道覆盖率门禁
JAVA_HOME=<jdk-21> PATH=<maven-3.9.x>/bin:$PATH mvn -o -B -ntp clean verify

# 前端：三件套
cd frontend && npm run lint && npm run test && npm run build
```

> 环境注意：本机 `PATH` 上的 Maven 3.6.3 / `JAVA_HOME` 指向的 jdk-11 会与 Java 21 编译不兼容（`<release>21</release>`）。可用组合为 **jdk-21 + Maven 3.9.9**。

## 7. M3 剩余范围与建议顺序

| 序 | 块 | 关键约束（来自 docs） |
|---|---|---|
| 1 | 算子试运行（选节点 + 实时日志 + 退出码 + `DRYRUN_OPERATOR`） | 需先补 **O-17**（`SshExecutorClient` 无测试）；试运行**不得**写任务/步骤实例表 |
| 2 | 工作流 CRUD + 版本化（`WF-####`、草稿/发布、42215 草稿变更待决） | 落地后回头补 **O-21** 的引用闸门正例 |
| 3 | 自研 SVG 画布编辑器（**D-14**） | 止损线：**超 10 人日即降级 LogicFlow**；机制注释按 D-26 第 4 条 |
| 4 | DAG 校验 8 条规则（42213 汇总回填；规则 7 = 引用未发布/已下线版本 → **42218**） | 与 `docs/06` §DAG 校验逐条对齐 |
| 5 | 六层变量覆盖链解析器（**D-20** 语法；42214） | 逐层覆盖优先级必须有可读的测试名，否则"哪层赢"永远说不清 |
| 6 | 触发器 CRON（42216）+ 时间窗（42217） | |
| 7 | 前端 6 页（算子列表/详情/版本 + 工作流列表/编辑器/详情） | 画布页单独排期 |

---

**本切片一句话总结**：算子域后端全链（含两条最容易做错的闸门——42211 引用闸门与 42210 错误聚合）已落地并实测；同时把 M2 留下的 `O-14` 覆盖率门禁**真正闭环**——4 个模块 5 道门禁全绿、经反向扰动验证会拦，并在补门禁的过程中抓出并修掉了一个 M2 遗留的配置缺陷（`*ConverterImpl` 从未被排除）和一处新代码零覆盖。收尾时又补了 CI 失败的上传报告步骤——**"过 CI"这件事本身也要可诊断**，不能只靠重跑赌运气（§5-6）。
