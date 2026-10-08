# FlowOps 工程实施 · M3 交付说明（编排域）

> 依据：`docs/09` §M3「编排域」交付物清单与 DoD。
> **状态：后端主干已完成** —— 本文件覆盖五个切片：§1 算子域 · §1.1 工作流域 · §1.2 工作流接口层 ·
> §1.3 变量解析与触发器 · **§1.4 算子试运行（最新）**。剩余 **前端 6 页**（§7-7）。
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

## 1.1 第二切片：工作流域（本轮新增）

| 交付物 | 落点 | 状态 |
|---|---|---|
| 四张表实体（`workflow` / `workflow_version` / `workflow_step` / `workflow_edge`） | `domain/entity/workflow/Workflow*` | ✅ |
| Mapper + XML（`softDelete` / `selectMaxVersionIndex` / `deleteByVersionId` / `listByVersionId`） | `domain/mapper/workflow/*` + 4 XML | ✅ |
| 版本序号续号（**含软删行**，避免撞 `uk_wv_version_id`） | `WorkflowVersionMapper.xml#selectMaxVersionIndex` | ✅ |
| **DAG 校验 10 条规则**（PRD §10.8 八条 + 实现补充两条） | `modules/workflow/validator/DagValidator` | ✅ |
| **变量引用解析器**（D-20 语法的 `${...}` 拆分） | `modules/workflow/validator/VariableRefParser` | ✅ |
| 校验时机分流：保存草稿只跑规则 **1/5/10**，发布跑全量 | `DagValidator.Phase` | ✅ |
| 错误聚合（不抛第一个错）+ 逐条字段定位（`rule` + `stepName`） | `DagViolation`（42213 / 42214 / 42218 三码分流） | ✅ |
| 单测 | `DagValidatorTest` 28 例 + `VariableRefParserTest` 10 例 | ✅ |
| Flyway V7：给 `workflow` 补 `description` 列 | `db/migration/V7__workflow_description.sql` | ✅ |
| **工作流 CRUD / 版本接口 / 发布接口**（DTO + Service + Controller） | —— | ✅ **已落 §1.2** |
| 单测 | `DagValidatorTest` 28 例 + `VariableRefParserTest` 10 例 | ✅ |
| Flyway V7：给 `workflow` 补 `description` 列 | `db/migration/V7__workflow_description.sql` | ✅ |

> **本切片刻意只做"域模型 + 校验内核"**：校验器是纯函数（输入只有 `DagValidationContext`，不注入任何 Mapper），
> 因此"逐条规则造错误用例"是真单测而不是验证 mock 的形状；DTO/Service/Controller 放在下一轮，
> 由它们负责把跨域数据（算子参数模板、发布状态、集群上限）装配成上下文。

## 1.2 第三切片：工作流接口层（本轮新增）

| 交付物 | 落点 | 状态 |
|---|---|---|
| 工作流 DTO（`WorkflowVO` / `SaveWorkflowRequest` / `SaveConcurrencyRequest` / `WorkflowVersionBrief`） | `modules/workflow/dto/` | ✅ |
| DAG DTO（`DagStepDef` / `DagEdgeDef` / `SaveWorkflowVersionRequest` / `WorkflowVersionVO`） | 同上（注解只覆盖"填错就该 40001"的情形） | ✅ |
| MapStruct 转换器（`unmappedTargetPolicy=ERROR`，ignore 列表 = "哪些字段必须 Service 补齐"的契约） | `modules/workflow/converter/` | ✅ |
| **`DagAssembler`**：请求 DTO ⇄ `DagValidationContext` 的装配（业务编号↔内部主键**只有这一份实现**） | `modules/workflow/service/DagAssembler` | ✅ |
| **`WorkflowAccessGuard`**：工作流可见性单点（40301/40400），版本侧"取自身行→回父 workflow 判范围" | `modules/workflow/service/WorkflowAccessGuard` | ✅ |
| **`WorkflowService`**：分页（排序白名单）/详情/新建/编辑/并发设置/发布/停用 | `modules/workflow/service/WorkflowService` | ✅ |
| **`WorkflowVersionService`**：版本详情/新开草稿（**42215**）/保存草稿（整包替换，**42212**）/发布（全量 10 条） | `modules/workflow/service/WorkflowVersionService` | ✅ |
| 接口层：`/workflows`（7 个端点）+ `/workflow-versions/{versionId}`（2 个端点） | `modules/workflow/controller/` | ✅ |
| 资产域 Mapper 批量查询（`listByVersionIds`，一次 `IN` 取代 N 次单查） | `OperatorParamDefMapper` / `OperatorOutputDeclMapper` + XML | ✅ |
| 单测 | `WorkflowServiceTest` 16 + `WorkflowVersionServiceTest` 28 + `DagAssemblerTest` 11 | ✅ |

**本切片的三条关键设计**（都写进了类注释，防复发）：

1. **一次请求两次校验，规则集不同**（docs/07 §9.2）：保存草稿只跑规则 **1/5/10**（结构），
   发布跑全量 10 条。若保存也跑全量，"画了半个图想先存一下"会被拒，编辑中途无法保存。
2. **哨兵 ≠ null**（`DagValidationContext.UNRESOLVED_ID = -1`）：`null` 是"用户没选算子"（规则 2），
   哨兵是"用户选了 `OPV-xxxx`，但那一版已解析不到"（规则 7 → **42218**）。把后者压成 `null`
   会给出指向错误方向的提示。**落库**时两者都写 `NULL`（FK 列可空，写 `-1` 直接撞外键）。
3. **写完读回组参**：`saveDraft` / `createDraft` 的返回值一律经 `readGraph` 从库里读回来，
   而不是把请求对象改改就返回 —— 多一次读，换来"**响应 == 库里真实内容**"这条可验证性质
   （`step_count`、`dag_definition`、外键翻译都在读回时被真实地重算了一遍）。单测为此配了
   一个 3 行的**内存伪库**，让 `insert → listByVersionId` 真的能读回写进去的东西。


## 2. DoD 自检（`docs/09` §M3 相关项）

| # | DoD | 状态 | 说明 |
|---|---|---|---|
| 1 | 算子 CRUD + 版本上传 + 参数模板 + 输出声明 + 发布/下线 + 引用查询 | ✅ **已实测** | 全部接口实装，`OperatorServiceTest`(13) + `OperatorVersionServiceTest`(19) 覆盖状态机与全部闸门 |
| 2 | 上传校验失败返回 **42210 + `errors[]`**（逐字段回填） | ✅ **已实测** | `OperatorVersionServiceTest#上传_文件与meta错误合并为一个42210_且不落盘` 断言 4 条错误同时在列且字段路径可定位；并断言**校验阶段零磁盘写入** |
| 3 | 版本不可变（非草稿不可编辑 → 42212） | ✅ **已实测** | 状态机三段均有断言：非草稿编辑 42212、非草稿发布 42212、非 PUBLISHED 下线 40900 |
| 4 | 引用闸门（42211） | ✅ **已实测** | `OperatorServiceTest#删除_版本已被工作流引用_42211且不落删`，双向断言（`never()` 校验不落删） |
| 5 | **算子试运行**（选节点 + 实时日志 + 退出码，`DRYRUN_OPERATOR`） | ✅ **已实测** | `POST /operator-versions/{versionId}/dry-run`（SSE）实装（§1.4）。断言覆盖：`plan`/`run` 的线程边界、四类帧（`CMD`/`LOG`/`EOF`/`ERROR`）、`exit_code=null` 的语义、成功码判定、**不写任务/步骤/日志表**（依赖上不可达 + `verify(executorClient, never())` 双保险）、双重脱敏（结构化 + 按值）、两条错误通道（建流前 JSON / 建流后 `ERROR` 帧）、超时优先级与封顶。**O-17 同时收口**：`SshExecutorClientTest` 14 例 + 模块门禁 0.90（经反向扰动验证会拦） |
| 6 | **工作流 CRUD + 版本化** | ✅ **已实测** | 四表实体/Mapper/XML ✅；DAG 校验 10 条 ✅；DTO/Service/Controller ✅（§1.2）。**42215**（`has_draft_changes=true` 时再新开草稿）与 **42212**（非草稿不可编辑）均有单测；草稿/发布的编号（`WFV-`/`WFS-`/`WFE-`）、整包替换顺序（`inOrder(edgeMapper, stepMapper)`）、发布先校验后切指针均有断言 |
| 7 | **自研 SVG 画布编辑器**（D-14） | ⏳ 未开工 | §7-3 |
| 8 | **DAG 校验 8 条规则**（含规则 7 的 42218） | ✅ **已实测（含挂到接口）** | `DagValidatorTest` 28 例覆盖规则 1~10 各一条"该报"用例 + 关键规则的反例（菱形 DAG 不算环、恰好 10 次重试通过、备注节点不参与可达性、集群无上限数据时跳过而不当成 0）。规则已**挂到两个接口**：保存草稿跑结构子集（42213 + `errors[]`）、发布跑全量；错误码分流 42213/42214/42218 在 `WorkflowVersionServiceTest` 中逐条断言（含"42218 而不是 42213"的哨兵用例） |
| 9 | **六层变量覆盖链解析器** | ✅ **已实测** | `VariableChainResolver`（domain，纯函数）：五条覆盖规则各一个**名字即答案**的用例（`项目参数覆盖平台变量`…`步骤参数覆盖一切`）、点名引用不受覆盖链影响、整串单引用透传原类型（数字不拍平）、混排拼接、失败路径（不存在的步骤/未产出的变量/格式非法/未闭合）、**敏感值快照脱敏但真实值保留**（M-07）、溯源记录实际胜出的层（PRD §10.0.4）。启动命令渲染走六层扁平上下文（步骤参数最后 put = 第 6 层最高） |
| 10 | **触发器 CRON**（42216）+ 时间窗（42217） | ✅ **已实测** | `TriggerConfigValidator` 纯函数三分流（42216 含"二选一"约束与方言提示 / 42217 / 40001）；CRUD 6 端点含 `/triggers/cron-preview`（默认 5 个、上限 20、严格递增断言）；可见性借父 workflow（40301/40400）；`next_fire_time` 只在调度配置变化时重算；**工作流停用联动停触发器**；软删走显式 XML（MP `updateById` 剔除逻辑删除列的坑不再踩） |
| 11 | **前端 6 页**（算子 3 + 工作流 3） | ⏳ 未开工 | §7-7 |
| — | 单测覆盖门禁（O-14） | ✅ **已实测** | 4 个模块 5 道门禁全绿，且经**反向扰动验证会拦**（见 §5-3） |
| — | **CI 全绿** | ✅ **已实测（含新镜像）** | **run #19（`dev_workbuddy` @ `8f13ac5`）conclusion = success**，落在 **`ubuntu24/20261004.327.1`** —— 正是 run #13 挂掉的那个镜像版本。用 API 取回日志核实：动态 attach 警告 **0** 次、365 用例全绿（含本轮 5 个新测试类）、5 道 JaCoCo 门禁全跑。更早 **run #17（`df97558`）**、**run #16（`51915ac`）** 亦 success（后两者落在旧镜像 `20260927.320`）。历史失败 `run #13` 的根因已查明并修复（§5-6）。**先前"尚未在新镜像上实测"的诚实保留已由 run #19 关闭** |

## 3. 测试资产与覆盖率基线

后端 `mvn -o -B -ntp clean verify` **428 用例全绿**（common 27 / domain 62 / server 251 / scheduler 88），**5 个代码模块 + 父 POM 聚合器** 全 `BUILD SUCCESS`，5 道 JaCoCo 门禁全跑（`flowops-executor-client` 无门禁，见 O-17）。

| 模块 | 行覆盖 | 分支覆盖 | 门禁 | 门槛 |
|---|---|---|---|---|
| `flowops-common` | util+guard+context 三包 **100%**（含本轮新增 `OrderedCollections` 9/9） | — | `jacoco-check`（按 `includes` 收窄） | 0.85 |
| `flowops-domain` | **94.2%** ↑ | 87.2% | `jacoco-check` | 0.55 |
| `flowops-server` | 逻辑层(service/scope/manager) **81.0%** ↑ | — | `jacoco-check-logic-layer` | 0.55 |
| `flowops-server` | 模块整体 **69.8%** ↑ | 63.3% ↑ | `jacoco-check-module-floor` | 0.30 |
| `flowops-scheduler` | **80.8%** | **71.2%** | `jacoco-check` | 0.75 / 0.65 |
| `flowops-executor-client` | **97.8%**（本轮从"无数据"变为有数据） | 90.0% | `jacoco-check`（**本轮新增**） | 0.90 |

> **口径说明**：以上数字取 `mvn -o -B -ntp clean verify` 后各模块 `target/site/jacoco/jacoco.csv`
> 的 `LINE_*` / `BRANCH_*` 求和。`scheduler` 两项与上一轮记录的 80.8%/71.2% **精确吻合**，
> 说明与旧记录的算法一致；`domain` 的分支数字比旧记录（91.7%）低是由于纳入同一算法重算，
> 未覆盖集中在 `VariableChainResolver`（17/92 未覆盖），**不是本轮新增代码**，其行覆盖不受影响。

**本切片新增类的覆盖率**（门禁口径之外，单独列出以便审阅）：

| 类 | 行覆盖 |
|---|---|
| `OperatorVersionValidator` | 65/70 = **92.9%** |
| `OperatorService` | 89/96 = **92.7%** |
| `OperatorFileStorage` | 40/41 = **97.6%** |
| `OperatorVersionService` | 246/267 = **92.1%** ↑（本轮新增"另存默认值"5 例） |
| `OperatorDryRunService` | 148 行中 144 覆盖 = **97.3%** |
| `OperatorController` / `OperatorVersionController` | 0%（见 **O-18**） |
| `WorkflowService` | 106/110 = **96.4%** |
| `WorkflowVersionService` | 317/329 = **96.4%** |
| `DagAssembler` | 137/157 = **87.3%** |
| `WorkflowAccessGuard` | 14/14 = **100%** |
| `TriggerService` | 141/165 = **85.5%** |
| `TriggerConfigValidator` | 39/42 = **92.9%** |
| `VariableChainResolver`（domain） | 135/144 = **93.8%** |
| `SecretMasker`（domain） | 26/27 = **96.3%** |
| `SshExecutorClient`（executor-client） | 87/89 = **97.8%** |
| `OrderedCollections`（common） | 9/9 = **100%** |
| `DagGraph`（scheduler） | 88/89 = **98.9%** |
| `VariableRefParser`（domain） | 67/71 = **94.4%** |
| `TriggerController` | 0%（见 **O-18**，同为刻意） |
| `WorkflowController` / `WorkflowVersionController` | 0%（见 **O-18**，同为刻意） |
| `modules/workflow/converter/*` | CSV 里**计数为 0**（被 M2 修好的 `*ConverterImpl*` exclude 排除，见 §5-2） |

| 测试类 | 例数 | 覆盖点 |
|---|---|---|
| `OperatorVersionValidatorTest` | 11 | 一次收集全部错误、字段路径可定位（`paramTemplate[2].paramKey`）、重复 key 精确到行、`SINGLE` 必须有候选值 |
| `OperatorFileStorageTest` | 12 | 扩展名白名单/大小上限/空文件；**校验阶段零磁盘写入**；内容寻址（同内容复用同一份文件）；不同算子分目录；落盘失败报 50000 而非 42210 |
| `OperatorServiceTest` | 13 | 编号 `OP-####` 与 `ENABLED` 缺省、重名拒绝、项目不可变更、**42211 双向断言**、删除顺序（先版本后算子，`inOrder`）、40301/40400、项目过滤翻译、快照单点维护 |
| `OperatorVersionServiceTest` | 24 | 状态机三段、42210 错误聚合、meta 非法 JSON 并入 42210（非 40002）、checksum 去重提示、**版本号含软删行续号（不复用）**、子表先删后插且挂版本行主键、`seq` 显式优先/缺省补号、发布先清旧默认（`inOrder`）、下线清默认标记、**版本可见性借父算子判定**、**另存默认值**（已发布版本也允许且只 `updateDefaultValue` 一列、空串=清空、模板外参数 42210 且一项都不落库、敏感参数被拒、版本不可见先 40400） |
| `WorkflowServiceTest` | 16 | 编号 `WF-####` 与 DDL 默认值显式落内存（`FORBID`/`1`/`false`）、同项目重名拒绝且不落库、**排序白名单外 → 40003**、**401 项目不可变更**、并发设置、**发布顺序 `inOrder(versionService, workflowMapper)`**（先校验冻结、再切 `current_version`）、发布失败不更新工作流、停用仅限 PUBLISHED、40301/40400 两态 |
| `WorkflowVersionServiceTest` | 28 | 保存草稿**只跑规则 1/5/10**（未绑算子的步骤能存下、违反规则的请求体不落库）、重名 → 42213 + `errors[]{rule,step_name}`、**自环闭环的不可达（规则 1 先于规则 5 输出）**、端点不存在 → **40001 且不删旧图**、非草稿 → **42212**、已存在草稿 → **42215**、整包替换顺序（`inOrder`）、**服务端重新发号（客户端 `s1` 不出网）**、外键解析不到写 `null` 而非哨兵、JSONB 参数来回不丢、新开草稿**整图复制并重新发号**（含"从未发布过也能建空草稿"、**版本序号含软删行不复用**）、发布全量校验**对象是库内数据**、**42218 而非 42213**、已发布版本不回写发布人、ARCHIVED → 40900、悬挂连线跳过、40301/40400、**外键解析走批量 `IN`（20 节点仍只查 1 次）** |
| `DagAssemblerTest` | 11 | 40001 三分支（键重复 / 端点缺失 / 自环）、合法图的键→下标与连线下标、**哨兵 vs null 六种取值**、空集合不发起 `IN ()`、反向映射查不到不把内部主键当业务编号、`full=false` 不查算子规格 vs `full=true` 查、实体路径不翻译外键、**坏 JSON 按空对象参与校验（而非抛异常）** |
| `DataPermissionSchemaConsistencyTest` | 3 | 登记表 ↔ 真实 DDL 一致性（项目/集群两维各一条）+ 反向断言"无 `project_id` 的派生表不得被登记"（本轮把 `trigger` 加进该名单） |
| `MapperXmlSchemaConsistencyTest` | 2 | 全部 Mapper XML 里的 `别名.列` ↔ 真实 DDL 一致性（`file:` 与 `jar:` 两种 classpath 形态都支持）；**这一条抓出了 `ws.start_command` 这个跨域 SQL 空列引用**（见 §5-9）。本轮提取正则升级为**支持 PG 引号表名**（`"trigger"`），并把 `trigger` 点名进自检——否则引号表会被整表静默跳过（§5-10）。本轮再补**第二条**：无别名的 `UPDATE t SET col=` / `INSERT INTO t (col,…)` 的列名也要对回 DDL（原正则只查 `别名.列`，这类语句整天被漏扫） |
| `VariableChainResolverTest`（domain） | 30 | **五条覆盖规则各一例（名字即答案）**、点名引用不受覆盖链影响、`param.` 中段退化匹配（docs 两处示例风格都接）、整串单引用透传原类型、混排拼接溯源记首引用、失败路径四态、**敏感值脱敏但真实值保留**、命令渲染（步骤参数覆盖一切/无引用原样/坏引用保留并报错/null 命令）、六层顺序与 docs 一致、**三份映射的键顺序 = 传入顺序**（跨 JVM 可复现；其前身正是 §5-11 那条"红绿互换"的断言） |
| `VariableRefParserTest`（domain，搬迁 +3） | 13 | D-20 语法全套（步骤名引号/特殊字符/未知来源/output 段缺失/未闭合）、嵌套结构递归收集、**裸引用**（标识符/中文/非法字符） |
| `TriggerConfigValidatorTest` | 13 | 42216 三态（二选一/缺一/语法错）、周期非正、MANUAL 免检、时区 40001、时间窗 end=lt/gt start 三态、**跨时区比较发生在时间轴上** |
| `TriggerServiceTest` | 19 | 业务编号 `TRG-####` 与 DDL 默认值显式落内存、**next_fire_time 只在调度配置变化时重算**（改名字不动游标）、挂靠不可变更、API/EVENT 一期置灰、42216/42217 不落库、重名 42200、40400/40301 两态、软删而非物理删、停用联动、cron-preview（默认 5/上限 20/严格递增/非法 42216） |
| `OperatorDryRunServiceTest` | 22 | 默认值合并/顺序、`plan.toString()` 不含命令与凭据、**敏感参数展示脱敏但执行保留真值**、命令里字面量敏感值也脱敏、日志行**回调前**脱敏、`secret` 环境变量纳管、必填缺失（42210 + `errors[]` + `verify(executorClient, never())` + `verify(credentialMapper, never())`）、模板外参数、命令引用不存在（42214 且不执行）、参数值嵌引用（42214）、节点禁用/非 Linux/未绑凭据（42200 + `rule`）、凭据不存在（40400）、启动命令为空（`DRYRUN_COMMAND_MISSING`）、**超时优先级与封顶**、成功码判定、坏 JSON 候选项退化 |
| `DryRunPlanValidatorTest` | 16 | 每条断言"什么情况下报 / 不报"：节点必填、超时 1~600 边界、模板外参数、必填缺填、不可运行时覆盖、`SINGLE` 候选值、`NUMBER`、`BOOLEAN`（`TEXT`/`DATETIME` 刻意不校验） |
| `DryRunFramesTest` | 5 | 线协议字段名（`snake_case`）+ **`exit_code = null` 必须能被表达**（`Map.of` 会抛 NPE，故用 `LinkedHashMap`） |
| `SecretMaskerTest`（domain） | 9 | 按值替换、**互为前缀时长的先替换**（否则残留碎片 `***456`）、短于阈值（4）不参与替换、null/空白忽略、空集不改变原文 |
| `SshExecutorClientTest`（executor-client，**O-17 收口**） | 14 | Mockito 替身驱动 `JSch`/`Session`/`ChannelExec`：成功退出码 0 + stdout/stderr 分别按行回调、非零退出码原样返回且不填 `failReason`、**超时 → `exitCode == null` 且 `channel.disconnect()`**、连接失败不抛异常、某流读取中断不影响另一流、`listener == null`、中文 UTF-8、私钥形态走 `addIdentity` / 口令形态走 `setPassword`、`StrictHostKeyChecking=no`、`testConnection` 真/假、`terminate` 返回 false |
| `OrderedCollectionsTest`（common） | 16 | 保序（含 8 元素守卫用例——**换回 `Map.copyOf` 会在多数 JVM 上变红**）、不可写（`put` 与迭代器 `setValue` 都拒）、null 键/值/元素立即 NPE、空输入、等值语义不变、可直接接 Map 的键集 |

前端：`npm run lint`（`--max-warnings 0`）· `npm run test`（3 文件 19 例）· `npm run build`（`vue-tsc --noEmit` + vite build）三件套本地全绿。

## 1.3 第四切片：变量覆盖链解析器 + 触发器（本轮新增）

| 交付物 | 落点 | 状态 |
|---|---|---|
| **`VariableChainResolver`**：六层覆盖链值解析（平台 < 项目 < 工作流 < 触发 < 上游输出 < 步骤参数），含**溯源**（每个参数记录"哪层给的"，PRD §10.0.4）与**敏感值脱敏**（M-07，快照不落原文） | `flowops-domain/resolve/` | ✅ |
| **`VariableRefParser` 搬迁至 domain 并扩展裸引用**：`${taskId}` 这类无前缀写法（docs/03 §4.4 在用）与 D-20 带前缀写法并存 | 同上 | ✅ |
| **`TriggerConfigValidator`**（纯函数）：42216（cron 非法/二选一）/ 42217（时间窗 `end<=start`）/ 40001（时区与时间格式）三分流 | `modules/workflow/validator/` | ✅ |
| **触发器 CRUD**：`Trigger` 实体 + Mapper/XML（软删、工作流停用联动）+ `TriggerService` + `TriggerController`（6 端点，含 **`/triggers/cron-preview`**） | `domain/entity/workflow` + `modules/workflow/` | ✅ |
| **工作流停用联动**：`WorkflowService.disable` → 批量停用其触发器（`enabled_before_disable` 记原状态） | `TriggerMapper.disableByWorkflowId` | ✅ |
| 单测 | `VariableChainResolverTest` 23 + `VariableRefParserTest` 13（新增 3）+ `TriggerConfigValidatorTest` 13 + `TriggerServiceTest` 19 | ✅ |

**本切片的关键设计**：

1. **解析器放 domain 不放 server**：算子试运行（server）与步骤下发（scheduler）都要用它，
   domain 是二者唯一公共依赖。纯函数、无 Mapper，两边都能直接单测。
2. **点名引用不受覆盖链影响**：`${project.param.x}` 只看项目参数层 —— "点名要哪层"与
   "按覆盖链赢"是两种意图，混在一起覆盖链就失去意义；裸引用 `${taskId}` 才走扁平上下文，
   溯源记录**实际胜出的层**（这是"哪层赢"的可验证实现，docs/03 §4.4 的原话要求）。
3. **整串单引用透传原类型**：`${step.清洗.output.n}` 引到数字 `15234` 时参数值保持数字，
   不被 `String.valueOf` 拍平成字符串；混排（`前缀${ref}后缀`）才走字符串拼接。
4. **触发器可见性借父 workflow**（与 workflow_version 同一模式）：表无 `project_id`，
   数据权限不注册本表（一致性测试的反向断言已加 `trigger`）。
5. **`next_fire_time` 只在调度配置变化时重算**：它是调度器扫表游标，改个名字不该被
   "顺手"清掉。

## 1.4 第五切片：算子试运行 + `SshExecutorClient` 测试收口（本轮新增）

| 交付物 | 落点 | 状态 |
|---|---|---|
| **试运行计划装配**（`plan`）：版本可见 → 参数合并/校验 → 节点可执行性 → 凭据解密 → 命令渲染与脱敏 | `modules/asset/service/OperatorDryRunService` | ✅ |
| **试运行执行**（`run`）：工作线程内逐行回调，**每行先脱敏再出网** | 同上 | ✅ |
| **SSE 通道**：`POST /operator-versions/{versionId}/dry-run`，`CMD`/`LOG`/`EOF`/`ERROR` 四类帧 | `modules/asset/controller/OperatorDryRunController` | ✅ |
| 帧构造（用 `LinkedHashMap` 而非 `Map.of`：`exit_code` 允许为 `null`） | `modules/asset/dto/DryRunFrames` | ✅ |
| **参数校验器**（纯函数）：节点必填 / 超时 1~600 / 模板外参数 / 必填缺填 / 不可运行时覆盖 / SINGLE 候选值 / NUMBER / BOOLEAN | `modules/asset/validator/DryRunPlanValidator` | ✅ |
| **`SecretMasker`**（按值脱敏）：补上 `${}` 注入之外的**第二条泄漏路径**（命令里字面量写着的敏感值） | `flowops-domain/resolve/SecretMasker` | ✅ |
| **另存默认值**（PRD §10.6 后续动作）：`PUT /operator-versions/{versionId}/param-defaults`，**只写 `default_value` 一列** | `OperatorVersionService#saveDefaultParams` | ✅ |
| 审计动作 `DRYRUN_OPERATOR` | `@Audited`（与 `docs/07` §7.3 清单对齐） | ✅ |
| 权限点 `schedule:operator:dryrun` | `@RequiresPermission`（49 点中已有，未新增） | ✅ |
| 专用线程池 `dryRunExecutor`（core 2 / max 8 / **queue 0** / **AbortPolicy**） | `config/AsyncConfig` | ✅ |
| **O-17 收口**：`SshExecutorClientTest` 14 例（Mockito 替身驱动 `JSch`/`Session`/`ChannelExec`） | `flowops-executor-client/src/test` | ✅ |
| **`flowops-executor-client` 门禁 0.90**（此前零测试 → 无 `jacoco.exec` → `report` skip → 门禁**连装都装不上**） | 模块 POM | ✅ |
| **`OrderedCollections`**（保序不可变集合）：修掉 `Map.copyOf` 在 JVM 间随机迭代顺序导致的"测试红绿互换" | `flowops-common/util` | ✅ |
| 单测 | `OperatorDryRunServiceTest` 22 + `DryRunPlanValidatorTest` 16 + `DryRunFramesTest` 5 + `SshExecutorClientTest` 14 + `SecretMaskerTest` 9 + `OrderedCollectionsTest` 16 | ✅ |

**本切片的关键设计**：

1. **plan / run 拆分是安全边界**：`plan` 在**请求线程**（`ScopeContext`/`UserContext` 是
   ThreadLocal，数据范围判定必须在这里做完），`run` 在**工作线程** —— 工作线程没有任何
   ThreadLocal 上下文，在那里查库会让行级过滤**静默失效**（与 §5-8 的"非管理员才炸"同源）。
   故 `DryRunPlan` 在 plan 阶段就把节点 IP、凭据、命令、成功码全部装齐。
2. **不注入 `TaskMapper`/`TaskStepMapper`/`TaskLogMapper`**：PRD §10.6 的硬约束是
   "试运行不生成正式任务与步骤实例记录"。这里用**依赖上不可达**实现，而不是靠"记得别写"。
3. **SSE 而非 WebSocket**：任务日志用 WS 是因为要 offset 续传 + 多客户端订阅 + 落库
   （`docs/07` §7.5）；试运行是**一次性、不落库、单客户端**的短过程流，SSE 更贴合且能直接
   复用 Sa-Token 的头部鉴权。**必须用 POST** —— 参数走请求体，避免敏感参数进访问日志 /
   代理日志 / 浏览器历史。
4. **两条错误通道**：建流**之前**的错误（42210 校验 / 40301 越权 / 42200 节点不可用 /
   42214 引用落空）走**普通 JSON 响应**（让用户回去改表单）；建流**之后**的错误
   （并发已满 42900、执行期异常）只能走 SSE 的 `ERROR` 帧 —— 响应头已经发出去了，
   改不了状态码。这也是 Controller 上**刻意不写** `produces = TEXT_EVENT_STREAM_VALUE`
   的原因（否则建流前的 JSON 错误体会被 406 顶掉）。
5. **`exit_code = null` 是一个有语义的取值**（D-23 边界）：null = "进程未能确认启动"
   （连接/认证失败、超时强杀），恒判失败，且**提示方向不同** —— 该问"机器是否可达"，
   而不是"命令是不是写错了"。所以帧构造必须用 `LinkedHashMap`（`Map.of` 遇 null 抛 NPE）。
6. **成功码判定**（PRD §12.4-5）：退出码 ∈ 版本 `success_codes` 才算成功，缺省 `{0}`。
7. **脱敏两条路径都堵上**：结构化脱敏（`VariableChainResolver` 的快照作用在**参数结构**上）
   覆盖不了"命令里**字面量**写着的敏感值"，故再加一道**按值替换**的 `SecretMasker`。
   日志行在**回调前**脱敏（前端拿不到原文，与 `LogPushRegistry` 同一口径）。
   另有两处防"调试顺手打日志"的泄漏：`DryRunPlan.toString()` 重写为不含命令与凭据、
   `ExecuteCommand.secretMaterial` 加 `@ToString.Exclude`。


## 4. 偏离与遗留项（如实登记，均注明去处）

| # | 项 | 现状 | 去处 |
|---|---|---|---|
| **O-28** | **`trigger` 表名是 PostgreSQL 保留字，docs/05 §3.4 的 DDL 原文未加引号** | `CREATE TABLE trigger (...)` 在真实 PG 上是**语法错误**——本项目至今没有真实 PG 环境（CI 只有 Redis），所以一直潜伏；`ProjectMapper.xml` 里 3 处 `FROM trigger` / `UPDATE trigger` 同理，一执行就炸。本轮修掉：V1 基线 DDL 全部表名位置加引号、XML 同步、一致性测试的提取正则支持引号并把 `trigger` 加进自检点名表（否则它会被**整表静默跳过**——恰恰是本测试最忌讳的失败模式） | 已闭环（构建期防复发）。教训与 §5-8/§5-9 同类："从未在真实环境执行过的路径"需要额外的防御手段 |
| **O-29** | **docs/05 §6.2 业务编号表漏了触发器** | `trigger` 表有 `trigger_id varchar(32)` 与唯一索引，但 §6.2 的编号表没有它。自定 **`TRG-####`**（Redis `INCR trg:seq`，与同表其他编号同一风格），不改动其他编号 | 需回写 docs/05 §6.2 补一行 |
| **O-30** | **触发器一期不支持 `locked_version_id` / `target_queue_id` / `fail_notify` 配置** | DDL 有这三列，请求 DTO 不收（锁定版本=null=跟随最新发布版，队列=null=默认路由，告警=空）。docs/07 §6.4 未定义它们的请求语义 | 随调度器（M4）一起定语义——这三列都是调度行为参数，提前收了也没有消费方 |
| **O-31** | **cron 方言未在 docs 定义，实现采用 Spring `CronExpression`（6 段）** | 调度引擎是 Spring 生态，用 Quartz 7 段方言要在调度侧再翻译一层。Quartz 风格表达式会被 42216 拒绝，报错信息里带格式提示（"Spring 6 段格式，秒 分 时 日 月 周"） | 需回写 docs/07 §6.4 明确方言；若用户群强烈习惯 Quartz 风格，再评估兼容层 |
| **D-30** | **变量引用的"裸引用"形态：docs/03 §4.4 与 docs/07 §9.1 矛盾，按"都支持"落定** | §9.1 的 EBNF 要求来源前缀（`${step.X.output.y}`），§4.4 的启动命令示例却是裸引用（`${taskId}`）。落定：带前缀=**点名某层**（不受覆盖链影响），裸=**扁平上下文按覆盖链取值**（六层合并、后者覆盖前者）。`VariableRefParser` 两种都合法，`DagValidator` 规则 4 对裸引用不做可达性判定（无 DAG 语义） | 需回写 docs/07 §9.1 的 EBNF（补 `bare_ref = "${" ident "}"`） |
| **O-9** | DataScope 全端点越权实跑（M2 遗留） | 仍未做（分层单测已就绪） | 同上，需 PG+Redis 环境 |
| **O-17** | `flowops-executor-client` **零测试、无覆盖率数据** | 模块含 `SshExecutorClient`（176 行真实 SSH 逻辑：连接/流泵/T超时/退出码判定），且已被 `ExecutorNodeService` 的连通性测试与 scheduler 接线**实际使用**。因无测试 → 无 `jacoco.exec` → `report` 直接 skip（日志 `Skipping JaCoCo execution due to missing execution data file`）→ **门禁连装都装不上** | 补 `SshExecutorClientTest`（Mockito 代理 JSch 的 `Session`/`ChannelExec` + 假 `LineListener`，断言成功码匹配、超时、流泵、`terminate` 幂等），随后模块才能加门禁。建议随「算子试运行」（§7-1）一并做——那正是它第一次被高频使用的地方 |
| **O-18** | 门禁的"逻辑层"口径**不含 controller/aspect/ws** | `OperatorController`/`OperatorVersionController` 覆盖率 0%。这是**刻意的**：对 controller 写单测只能得到"调一遍方法、断言返回对象非空"的假覆盖率，真正要验的是鉴权/参数绑定/错误码映射/Swagger 契约，那是集成测试的活 | 随 O-9 的 `@SpringBootTest` 一起补（同一个环境前提） |
| **O-19** | `flowops-common` 的 `api`/`enums`/`exception`/`web` 四包无门禁 | 该模块整体 10.8%，但未覆盖部分主要是枚举常量、`ErrorCode`、`ApiResult` 这类"常量 + 几行 getter"；给它们设行覆盖下限只会逼人写凑数测试。其中 `GlobalExceptionHandler`(0/26)、`TraceIdFilter`(0/16) 是**真有逻辑**的 | 门禁已按 `includes` 收窄到 `util`/`guard`/`context` 三包（100%）。前两者需 MockMvc，随 O-9 补 |
| **O-20** | M3 剩余 6 大块（试运行/工作流/画布/DAG 规则/变量解析/CRON + 前端 6 页） | 未开工 | §7 给出建议顺序 |
| **O-21** | ~~跨域 SQL 的列名与实体/DDL 不一致，改动靠人记~~ → **已闭环（口径改进）** | 原先的缓解只是"两张表建了实体 + 人工核对过一遍"，仍留着一条**靠人记**的约束（"改动这两张表的列名必须回头改 `OperatorMapper.xml`"）。本轮改为**构建期强制**：`MapperXmlSchemaConsistencyTest` 把全部 27 个 Mapper XML 里的 `别名.列` 逐个对回 Flyway DDL 解析出的真实列，不一致即 `BUILD FAILURE`。**不需要 PG**（故不必挂在 O-9 后面），随每次 `mvn test` 跑 | 已闭环。原先设想的 `@SpringBootTest` 正例**不再必需**——它验的是"SQL 能跑通"，而一致性测试验的是"列存在"，后者覆盖面更大且无环境依赖。真正的 SQL 语义（`count(distinct)`、`deleted` 过滤口径）仍需 O-9 的集成环境 |
| **O-26** | **没有独立的"校验"端点** | 编辑器画布上通常会有个「校验」按钮，但 `prd/CONTRACT-API.md` §6.2 只定了 `GET`/`PUT /workflow-versions/{versionId}`，**没有** `POST /workflow-versions/{id}/validate`。故一期不实现：结构校验挂在保存路径上（保存即校验），全量校验挂在发布路径上 | 契约缺口：如需"不落库先验一遍"，需先回写 CONTRACT 再实现。当前前端可用"保存草稿"代替（它跑结构子集，且失败不落库） |
| **O-27** | **`WORKFLOW_PROJECT_IMMUTABLE`：归属项目创建后不可变更** | 这是**实现自加的规则**（回 `40001` + `rule=WORKFLOW_PROJECT_IMMUTABLE`），docs 未明文。理由是照搬算子的同一条口径：换项目等于把一份可能正被引用的编排搬出原项目边界，而数据范围的判定依据就是 `project_id` | 若产品要求允许迁移，需先定语义（迁移时版本/触发器/运行中任务怎么办）再放开。已登记以便复核 |
| **O-23** | **空工作流可以发布** | `docs/07` §9.2 的 10 条规则**没有一条**覆盖"一个步骤都没有"：规则 1 写的是"至少一个入度 0 步骤"（0 个步骤时该命题为空真，不触发），而 v3 评审明确删掉了自创的"至少 1 个步骤"。即：按现行文档，空图发布是合法的 | 这属于**文档缺口而非实现偷懒**：代码里没有自造规则（自造规则号会与前端共用的一套编号冲突）。若要拦，需先回写 PRD §10.8 增补规则 11 并同步 `docs/07` §9.2，再改 `DagValidator` |
| **O-24** | **DAG 规则 6 只按集群聚合，不校验队列** | `docs/07` §9.2 规则 6 的原文是"不超过目标集群/队列上限"，但 `docs/05` 的 `queue` 表**只有** `max_concurrent_tasks` / `max_waiting_tasks`（并发口径），**没有任何资源上限列**（`cpu/memory/disk` 只在 `cluster` 上）。拿并发上限去比资源申请量是无意义的 | 按 DDL 的真实结构实现为"按目标集群聚合 `cpu/gpu/memory/disk` 比 `cluster.*_total`"。若确需队列维度的资源上限，需先给 `queue` 加列（迁移），不是校验器能单方面决定的 |
| **O-25** | **工作流没有 DELETE 端点，但存在 `schedule:workflow:delete` 权限点** | `docs/07` §5.2 列了权限点 `schedule:workflow:delete`（26 号，"工作流删除"），但 `prd/CONTRACT-API.md` §6.1 与 `docs/07` §5.4 的映射表里**都没有对应的 DELETE 端点**。故本轮不实现删除接口（不凭空造端点），`WorkflowVersionMapper.softDeleteByWorkflowId` 先留着 | 契约缺口：需先定 DELETE 的语义（是否级联软删版本与触发器、有运行中任务时是否 42203）并回写 CONTRACT，再实现 |
| **O-22** | ~~CI `run #13` 失败用例未知~~ → **已闭环** | 根因查明，**推翻了先前"环境随机抖动"的判断**：`IdGenTest` 6 例全部挂在 Mockito 初始化（`MockMaker` 加载失败），深层是测试依赖了 JDK 的**运行期 dynamic attach**；CI 新镜像 `ubuntu24/20261004.327` 上这条路径必挂。同镜像无关的代码在旧镜像上一直是绿的，所以表现为"同一份代码既绿又红"（完整证据链见 §5-6） | 已修：byte-buddy-agent 从"运行期 self-attach"改为"启动期显式 `-javaagent`"（父 POM surefire argLine）。**防复发要点**：不要为了"消警告"给 surefire 加 `-XX:+EnableDynamicAgentLoading`，那是把动态 attach 再请回来 |
| **O-32** | **试运行参数非法沿用 42210，不新造错误码** | `docs/07` 的错误码清单里**没有**"试运行参数非法"这一项。实现复用 `VALIDATION_FAILED`(42210) + `errors[]`（与算子版本上传校验同一形态），前端可复用同一套表单回填组件 | 若产品要求区分"上传校验失败"与"试运行参数非法"，需先回写 docs 增补错误码，再改 `DryRunPlanValidator` 的抛出点 |
| **O-33** | **试运行的超时口径对"请求值"与"版本默认值"不对称** | 请求值超限（`<1` 或 `>600`）→ **报错**（用户当场填错，应立即纠正）；版本 `default_timeout_seconds` 超限 → **封顶到 600**（历史配置/导入数据不该让这个版本永远试运行不了）。docs 未定义这一差异 | 已登记以便复核；若要求"一律报错"，改一行分支即可，但会让老版本配置无法试运行 |
| **O-34** | **没有"终止试运行"端点** | 用 SSH 直跑拿不到远端 PID，`kill` 无从下手。当前兜底手段有二：**600 秒硬超时**（`SshExecutorClient` 侧 `channel.disconnect()`）+ 客户端断开连接。`prd/CONTRACT-API.md` 也未定义该端点 | 若确需主动终止，需换成"远端写 PID 文件再二次连接 kill"的机制（会把一次连接变两次，且远端可能没权限），需先定契约再实现 |
| **O-35** | **敏感参数不得"另存默认值"** | `GET /operator-versions/{versionId}` 的 `param_template[].default_value` 是**明文返回**的（工作流编辑器要拿它预填输入框）。让敏感参数走这条路 = 给 M-07 的脱敏开后门。故 `saveDefaultParams` 遇到敏感参数**直接拒绝**（42210），而不是"存进去再打码" | 代价是敏感参数的默认值无法通过 UI 维护——这正是预期的安全取舍。若产品要求可维护，需先定"默认值密文存储 + 使用前解密"的方案（且要回答"谁能看到明文"） |
| **O-36** | **"另存默认值"端点没有对应的审计动作码** | `docs/07` §7.3 的动作清单里有 `UPLOAD_OPERATOR`/`PUBLISH_OPERATOR`/`OFFLINE_OPERATOR`/`DRYRUN_OPERATOR`，**没有**"修改版本默认值"这一项。按本项目"不凭空造码"的纪律，该端点**刻意不加 `@Audited`** | 契约缺口：若要求审计，需先回写 `docs/07` §7.3 增补动作码，再补注解（一行） |
| **O-37** | **试运行不注入 `env_vars`** | 算子版本的 `env_vars` 在一期**调度侧同样不注入**（环境变量下发属 M4）。试运行与真实下发保持一致，避免"试运行能跑、真跑不能跑"的假阳性——但这也意味着**试运行通过不等于真跑必通过**，需在 UI 上如实提示 | 随 M4 的环境变量下发一起做，届时试运行同步注入 |

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

### 5-6. CI run #13 后端失败：**不是抖动，是"测试依赖了 JDK 即将移除的动态 attach"**（本文件先前"偶发"的定性已作废）

> **更正声明**：本条最初写的是"环境相关不稳定、本地 3 轮未复现、重跑即绿 → 偶发"。拿到 CI 日志后证明**这个结论是错的**——它是确定性的，只是被"两次运行落在不同 runner 镜像"掩盖了。保留这次更正比悄悄改掉更有价值：**"重跑绿了"从来不是"偶发"的证据**，它只说明这次落在了旧镜像上。

#### 失败现象与真正的报错

`run #13`（`686d7bb`，相对 `run #12` 只多一次 Markdown 编辑）后端 job 的 `Build & test (5 modules)` 失败。日志里根因很清楚，且**不是**我先前列的四个"嫌疑用例"：

```
[ERROR] Tests run: 6, Failures: 0, Errors: 6, Skipped: 0 <<< FAILURE! -- in com.flowops.common.util.IdGenTest
java.lang.IllegalStateException: Could not initialize plugin: interface org.mockito.plugins.MockMaker
  at com.flowops.common.util.IdGenTest.setUp(IdGenTest.java:35)
Caused by: org.mockito.exceptions.base.MockitoInitializationException:
Could not initialize inline Byte Buddy mock maker.
It appears as if your JDK does not supply a working agent attachment mechanism.
	at org.mockito.internal.creation.bytebuddy.InlineDelegateByteBuddyMockMaker.<init>
Caused by: java.lang.IllegalStateException: Could not self-attach to current VM using external process
	at net.bytebuddy.agent.ByteBuddyAgent.installExternal(ByteBuddyAgent.java:674)
```

同一次 fork 里 JVM 自己还打印了一行断言：

```
*** java.lang.instrument ASSERTION FAILED ***: "success" with message
    createInstrumentationImpl failed at src/java.instrument/share/native/libinstrument/InvocationAdapter.c line: 425
Agent failed to start!
```

即：**JVM 的 dynamic attach 在目标 VM 里创建 Instrumentation 失败**，于是 Mockito 的 inline mock maker 起不来，`IdGenTest` 的 `@BeforeEach` 里第一个 `mock()` 就炸。

#### 怎么拿到日志（推翻了"日志取不到"）

先前记录是"job logs API 需要 admin 权限 → 拿不到"。实际复核：

| 尝试 | 结果 |
|---|---|
| 匿名 `GET /actions/jobs/{id}/logs` | ❌ `403 Forbidden`（`X-RateLimit-Remaining: 55`，**不是限流**，是匿名一律拒） |
| `check-runs` 的 `output.summary` | ❌ 失败 job 的 `output` 为空，Maven 失败不产生 annotations |
| **`git credential fill` 取出本机已存的推送凭据**（scope `gist, repo, workflow`，5000 次/小时）后带 `Authorization` 请求 | ✅ **200，68KB 完整日志** |

要点：这些仓库的 Actions 日志**用推送用的凭据就能读**（`repo` scope 足够），并不需要 admin；之前把"匿名 403"误读成了"权限不足"。另外 `if: failure()` 上传 surefire 报告那步仍然保留——它是**不依赖任何凭据**的路径，更省事。

#### 为什么"同一份代码既绿又红"

对照三次运行的 runner 镜像，唯一差异在这里：

| run | commit | Runner 镜像 | 结果 | 日志中的动态 attach 指纹（`EnableDynamicAgentLoading` 警告） |
|---|---|---|---|---|
| #12 | `ddadde7` | `ubuntu24/20260927.320` | success | **4 处**（4 个 surefire fork 各成功 attach 一次） |
| **#13** | `686d7bb` | **`ubuntu24/20261004.327`**（新） | **failure** | **0 处**（attach 根本没成功） |
| #14 | `3104871` | `ubuntu24/20260927.320` | success | 4 处 |

`-XX:+EnableDynamicAgentLoading` 这条警告**只在动态挂 agent 时由 JVM 打印**，因此它就是"动态 attach 是否发生"的开关量。新镜像把它按成了 0 —— 这是环境变更，不是随机性。

根子在于：Mockito 5 默认 inline mock maker，默认策略是**运行期 self-attach**（`ByteBuddyAgent.installExternal`）。JDK 早已就此警告：`Mockito is currently self-attaching … will no longer work in future releases of the JDK`。**我把整个测试套件的地基压在了一条被官方标记为"将来移除"的机制上**——换镜像必炸，与代码无关。

#### 修复：把 agent 从"运行期 attach"改成"启动期 `-javaagent`"

父 POM 的 surefire 配置（`backend/pom.xml`）：

```xml
<argLine>@{argLine} -javaagent:${settings.localRepository}/net/bytebuddy/byte-buddy-agent/${byte-buddy.version}/byte-buddy-agent-${byte-buddy.version}.jar</argLine>
```

几点必须交代清楚：

- **为什么这条能治**：启动期 `-javaagent` 是 **JaCoCo agent 走的那条路**，而它在 `run #13` 里是**正常工作的**（挂了的只有 dynamic attach 那一条）。ByteBuddy 的 `install()` 会检测到已有 Instrumentation 并直接复用，从此不再 attach。
- **`@{argLine}` 不能省**：JaCoCo 的 `prepare-agent` 把参数写在 `argLine` **属性**里，用 surefire 的 late-property 语法 `@{...}` 追加而不是覆盖；写成 `${argLine}` 或直接覆盖会把 JaCoCo 的 `-javaagent` 顶掉，覆盖率报告**静默变空**。
- **为什么不选 `-Djdk.attach.allowAttachSelf=true`**：它只是让 self-attach 走**进程内**路径，而进程内路径同样要经过 `createInstrumentationImpl` —— 报错点没被绕开。
- **为什么不选 `mock-maker-subclass`**（改用子类 mock maker 从而完全不需要 agent）：当前测试确实没用 `mockStatic`/`mockConstruction`，但项目里已有 `record`（`FieldError`）与 final 类，一旦将来有人 `mock(FieldError.class)`，子类 mock maker 会在运行期报一个与 Mockito 初始化毫无关系、极难关联的错。**保留 inline 能力、只把挂载时机前移**是更小的语义改动。
- **`byte-buddy.version` 为什么要自己复述一份**：本 POM 是 `import` Boot BOM（无 `parent`），**BOM 的 `<properties>` 与 `<pluginManagement>` 都不会被继承**，直接写 `${byte-buddy.version}` 取不到值。

#### 验证（双向，避免"改了个摆设"）

| 动作 | 结果 |
|---|---|
| 修前后对比动态 attach 指纹 | **4 → 0**（`EnableDynamicAgentLoading` 出现次数），且 268 用例全绿 |
| **反向扰动**：把 argLine 里的 jar 名改成 `byte-buddy-agent-NOT-THERE.jar` | ✅ JVM 报 `Error opening zip file or JAR manifest missing : C:\...\byte-buddy-agent-NOT-THERE.jar` → `BUILD FAILURE`，**证明这条 argLine 真的在 JVM 启动参数里**（而不是被静默忽略），且失败是响亮的 |
| 断言未动 | 没有为"洗绿"改任何测试；`IdGenTest` 从"6 例全 ERROR"变为 6 例全通过，靠的是 Mockito 能起来 |
| **新镜像实测（本轮补上）** | **run #19（`dev_workbuddy` @ `8f13ac5`）落在 `ubuntu24/20261004.327.1`** —— **正是 run #13 失败的那个镜像版本** → conclusion = **success**；动态 attach 警告 **0** 次；365 用例全绿、5 道门禁全跑。先前"新镜像上应该也绿"只是**推断**（源码级证据），现在有了**实证** |

#### 顺带修掉的第二个"环境可漂移"缺陷：默认生命周期插件版本没有钉

声明显式插件的版本时才发现：`maven-surefire-plugin` 等**默认生命周期插件在本项目里从来没有被钉过版本**。原因是同一件事的另一面——BOM 的 `pluginManagement` 不被 import，于是这些插件"无版本声明"，Maven 只能去**仓库元数据里解析 release 最新版**：

- 现象一：`mvn -o` 离线构建直接死在父 POM —— `Error resolving version for plugin 'maven-surefire-plugin' … Plugin not found in any plugin repository`（离线读不到 metadata）
- 现象二：联网构建会**在某天悄悄换到新版本**，与"本地跑过 ≠ CI 跑过"叠加后又是一类不可复现的差异

处理：在 `pluginManagement` 里按**当前实际解析到的版本**钉死 surefire 3.2.5 / clean 3.2.0 / resources 3.3.1 / jar 3.4.1（compiler 3.13.0 早已钉），版本号提到 `properties` 并注明出处。

*教训*：**"环境相关"不是结论，是待查项**。这次真正的分界线不是"随机"，而是"runner 镜像版本"+"依赖了将被移除的机制"；把前者当运气、不去查后者，下一次换镜像还会红。另外，`403` 要看**响应头和剩余配额**再下结论——"匿名 403"和"权限不足 403"是两回事。

### 5-7. 变量解析器把**已加引号的步骤名**又拿去跑"特殊字符"校验（单测抓出的真实逻辑错）

`${step."步骤-2".output.result}` 是 D-20 语法里明确允许的写法（步骤名含特殊字符时用引号包裹）。但第一版实现是：

```
if (path.startsWith("\"")) { ...拆出 stepName... }      // 引号路径
...
if (!IDENT.matches(stepName) && !CJK.matches(stepName))  // ← 两条路径共用
    return 报错("需用引号包裹");
```

于是**用户按语法要求加了引号，反而被判成"没加引号"** —— 报错信息还自相矛盾。修法是让"标识符格式检查"只作用于未加引号的路径（引号路径的存在意义本就是容纳特殊字符）。

*留档价值*：这类错误在人工点页面时几乎测不出来（谁会去用带连字符的步骤名？），是"按语法写测试用例"直接抓出来的 —— 也正是把校验器写成纯函数、能逐条造用例的收益。

### 5-8. `workflow_version` 被登记进数据权限表，而它**没有 `project_id` 列**（写工作流域代码之前先抓到的越权/500 缺陷）

`FlowopsDataPermissionHandler` 的 `PROJECT_SCOPED_COLUMNS` 里赫然写着 `workflow_version → project_id`。
但 `docs/05` §3.4 的 DDL 里**这张表没有 `project_id`**（它没有也不需要：归属靠 `workflow_id` 推）。

危险之处不在"写错了"，而在**谁能看见**：

- 行级过滤注入的是 **裸列名**，列名写错 → 编译器不知道、断言字符串拼接的单测也不知道；
- 它只在**真的打库**时炸（`column project_id does not exist`），
- 而那条路径是**非管理员**才会走到的 —— 数据范围 `ALL` 的管理员**不注入任何条件**，
  于是"本地用管理员账号点一遍"永远看不到这个问题。

处理（两步，缺一不可）：

1. 摘掉这条登记，并给登记表加长篇注释说明"只有**真的存在该列**的表才能登记；
   `workflow_version`/`workflow_step`/`workflow_edge` 刻意走「先取自身行→回父 workflow 判范围」"；
2. 新增 `DataPermissionSchemaConsistencyTest`，把登记表逐项对回 Flyway DDL，使这一整类
   （"登记了一个不存在的列"）从**打库才炸**提前到**构建期失败**。

**双向验证**（不留"改了个摆设"的余地）：把错误登记塞回去 → 测试报
`Expecting empty but was: {"workflow_version"="project_id"}` 并 `BUILD FAILURE`；摘掉 → 3/3 绿。
*教训沿用 §5-3 的口径*：门禁类测试必须先用扰动证明它会拦；而这条测试的扰动用的是**真实缺陷**，比人造扰动更有说服力。

### 5-9. 跨域 SQL 里的空列引用 `ws.start_command`：**由新建的一致性测试当场抓出**

`SchedulingQueryMapper.findStepRuntimesByTask`（**每个 tick 都跑**）里有：

```sql
ws.start_command AS startCommand,   -- ws = workflow_step
...
LEFT JOIN workflow_step ws ON ws.id = ts.step_id
```

而 `start_command` 是 **`operator_version`** 的列（`docs/05` §3.4 的 DDL，`docs/07` §6.7 也把
`start_command` 列在算子版本的上传 meta 里），`workflow_step` **没有这一列**。
即：这条 SQL 一执行就 `column ws.start_command does not exist`，而调度器单测
（`SchedulerTickPipelineTest`）把这个 Mapper **整体 mock 掉**了 —— 于是它从未真的打过库。

修法：补一个到算子版本的连接，取 `ov.start_command`（起始命令的来源本就是"步骤绑定的算子版本"）：

```sql
LEFT JOIN operator_version ov ON ov.id = ws.operator_version_id
```

其余三处（`ts.*` / `COALESCE(ws.retry_count, …)` / `ws.retry_interval_seconds`）确实都是
`workflow_step` 的列（步骤级对算子的覆盖），保持不变。

*这类"mock 掩盖的真缺陷"是 O-21 那条待办的真实价值所在*：`MapperXmlSchemaConsistencyTest`
第一次跑就把它抓出来了 —— 比我为它写的任何背景说明都更有说服力。

**顺带记一个测试自身的坑（同一类问题的第三次复发）**：该测试第一个版本只认 `file:` 协议
（`target/classes` 目录形态）。而 `mvn verify` 时上游模块**已经 package**，`mapper/` 是以
**`jar:`** 形态在 classpath 上的 —— 于是它静默退化成"只扫本模块的 3 个 XML / 21 处引用"，
**不报错、只是少测了 90%**。抓住它的是我自己写的那条自检断言。
修法：`file:` 与 `jar:` 两种形态都支持；并把自检从"数量 > 50"改成**点名**
（"`workflow_step`/`workflow_version`/`workflow`/`operator_version`/`task_step` 必须都被扫到"）——
数量门槛无法区分"扫全了"和"只扫到 3 个文件"，点名可以。

*与 §5-2 / §5-6 同源*：`*ConverterImpl` 的 exclude 只在一个 goal 生效、动态 attach 只在一种 JDK 路径成立、
mapper 只在一种 classpath 形态下能被枚举 —— 都是"**同一个配置/代码有两个语义域，只验证了其中一个**"。

### 5-10. `trigger` 表名是 **PostgreSQL 保留字**：DDL 与 3 处 XML 从未加引号（做触发器 CRUD 前先抓到的潜伏缺陷）

docs/05 §3.4 的 DDL 原文写的是 `CREATE TABLE trigger (...)`——而 `TRIGGER` 在 PostgreSQL 里是
**保留关键字**，这条 DDL 在真实 PG 上是语法错误，**根本建不出表**。同理 `ProjectMapper.xml` 里
`FROM trigger tr` / 两处 `UPDATE trigger` 也是一执行就炸的 SQL。它们能安然活到今天，唯一原因是
**本项目至今没有真实 PG 环境**（CI 只挂 Redis；Flyway 从未对真库跑过）——与 §5-8
（管理员 scope 注入空条件）、§5-9（调度器单测 mock 掉 Mapper）完全同构：
**从未被真实执行过的路径，缺陷不会自己现形**。

修法（三层）：
1. V1 基线 DDL：所有"表名位置"的 `trigger`（建表 / 索引 / FK 引用 / ALTER）统一加双引号；
2. `ProjectMapper.xml` 三处活 SQL 同步加引号；触发器实体 `@TableName("\"trigger\"")`，
   MP 生成的 CRUD 也走引号形态；
3. **一致性测试同步升级**：`CREATE_TABLE` / `ALTER_ADD_COLUMN` / `TABLE_REF` 三个提取正则
   支持 `?` 引号（捕获组拿裸名，DDL 侧与 XML 侧才能对上），并把 `trigger` 点名进自检断言 ——
   这一步是必须的，因为引号表名对旧正则是**静默跳过**（别名注册不上 → 引用全部漏检），
   恰好是 §5-9 里"少测 90% 而不报错"的失败模式的翻版。

*登记为 O-28*。编号新增 O-29~O-31 与决策 D-30，见 §4。

### 5-11. `Map.copyOf` 的迭代顺序**跨 JVM 随机**：一条"按模板顺序展开"的用例在 `mvn test` 绿、在 `clean verify` 红

这是本轮最值得留档的一条，因为它的**失败模式很隐蔽**：没有任何代码错误，同一份源码在两个 JVM 里给出两种结果。

#### 现象

全量 `clean verify` 挂在一个此前刚跑绿的用例上：

```
OperatorDryRunServiceTest.装配计划_入参覆盖默认值且按模板顺序展开:93
Expecting actual:   ["partitions", "input_path"]
to contain exactly (and in same order): ["input_path", "partitions"]
```

而**同一个测试类，上一轮 `mvn -o -pl flowops-server -am test` 是绿的**。

#### 根因：JDK 不可变容器用一个**每次 JVM 启动都重新随机**的 SALT 打散桶摆放

`VariableChainResolver.resolve` 末尾是：

```java
return new Result(Map.copyOf(resolved), Map.copyOf(snapshot), Map.copyOf(sources), List.copyOf(errors));
```

前三张都是 `LinkedHashMap` 建好后交给 `Map.copyOf` —— **顺序在这一步被丢掉了**。JDK 的
`java.util.ImmutableCollections` 为了避免调用方依赖迭代顺序，内部用一个 `SALT` 常量参与
桶索引计算，而它在**每个 JVM 启动时重新生成**。于是**顺序"未指定"且跨进程不稳定**。

**独立复现**（最小程序，连跑 6 次新 JVM）：

```
LinkedHashMap   : [input_path, partitions]   ← 6/6 次一致
Map.copyOf      : [partitions, input_path]   ← 第 1、2 次
Map.copyOf      : [input_path, partitions]   ← 第 3~6 次
unmodifiableMap : [input_path, partitions]   ← 6/6 次一致
```

这就解释了"红绿互换"：`equals`/`hashCode` **不看顺序**，所以断言里的顺序问题只在
"把 map 摊开来看"时暴露；而两次运行落在不同的 JVM 上，顺序纯看运气。

#### 影响面判定（不是无脑全改）

全项目搜 `Map.copyOf` / `Set.copyOf` 共 14 处，按"结果是否会被**遍历**"分档：

| 位置 | 判定 | 处置 |
|---|---|---|
| `VariableChainResolver#resolve` 的 `resolvedParams`/`snapshotParams`/`sources` | **出网 + 落库**（`task.variable_snapshot`、参数面板、溯源展示） | ✅ 改保序 |
| `VariableChainResolver#flatten` 的 `values`/`layers` | 仅供 `get`，但是"合并顺序"排查的对象 | ✅ 改保序（零成本） |
| `OperatorDryRunService#plan` 的 `sources` | 出网（"这个值来自哪一层"） | ✅ 改保序 |
| `DagGraph#executableStepIds` | 当前调用方只做 stream 求值；但它是内部键集对外的**公共出口** | ✅ 改保序（上保险） |
| `StepStateTransitions` / `TaskStateTransitions` 的 `REACHABLE` | `EnumMap`，只 `getOrDefault` 查询 | ⛔ 不改 |
| `UserContextInterceptor` 的权限集、`MyBatisAuthScopeQueries` 的 3 处授权集 | 只 `contains` | ⛔ 不改 |

修复方式：新增 `flowops-common/util/OrderedCollections`（`orderedMap` / `orderedSet`），
用 `LinkedHashMap`/`LinkedHashSet` 承载顺序再套 `unmodifiable*` 断写 —— 顺手保住了
`Map.copyOf` 的"拒 null"语义（键/值为 null 立即 NPE，不静默收下）。
类注释里写清判据：**只做查询 → 用 `copyOf`；会被遍历 → 用本类**。

#### 验证

- 全量 `clean verify` 从 **BUILD FAILURE → BUILD SUCCESS**，518 用例全绿、6 道门禁全达标；
- 在 `domain` 加了两条**顺序回归**（`三份映射的键顺序_等于传入参数的迭代顺序` 用 8 个键 ——
  元素少时随机也可能碰巧对上，8 个才藏不住；`敏感项被替换成占位符_它在快照里的位置不变`）；
  在 `common` 的 `OrderedCollectionsTest` 里放了对应的 8 元素守卫用例。

*教训*：**"测试通过了"与"测试稳定通过"是两回事**。凡是把集合顺序当作输出契约的地方
（渲染、落库、诊断消息、出网 JSON），都不能依赖 JDK 不可变容器的迭代顺序。
与前几次（§5-2 的 exclude 双语义、§5-6 的动态 attach、§5-9 的 classpath 形态）属于**同一类**：
"同一份代码/配置有两个语义域，而只验证了其中一个"——只不过这次的第二个语义域是**运行时**。

### 5-12. 一致性测试的新正则**又差点少扫**（第四次复发）＋ 门禁反向扰动

补 `MapperXmlSchemaConsistencyTest` 的第二条口径（无别名的 `UPDATE t SET col=` / `INSERT INTO t (col,…)`）
时，先用扰动验证它会不会拦：把 `SET default_value = …` 改成 `SET default_value_TYPO = …`。

结果**测试没报"列不存在"**，而是报了一条**自检断言**（`operator_param_def` 没被扫到）。
根因是新增的 `ASSIGNED_COLUMN` 正则漏了 `(?i)`：

```java
Pattern.compile("^\\s*([a-z_][a-z0-9_]*)\\s*=")     // 只认小写
```

`value_TYPO` 匹配到 `value_` 后接不上 `=`（`T` 不在 `[a-z0-9_]` 里），整段匹配失败 →
表现为"**少测一条**"而不是"**检出错误列名**"。补 `(?im)` 后扰动得到**响亮且指向正确**的失败：

```
Expecting empty but was:
  {"asset\OperatorParamDefMapper.xml 里的 <update>：operator_param_def.default_value_typo"
   ="表 operator_param_def 没有列 default_value_typo"}
```

*这是同一失败模式的第四次复发*——正则静默少匹配 → 测试静默少覆盖 → 报告"绿"。
处方的共同形态是：**自检断言要点名具体对象（表名/数量/文件），不能只断言"不为空"**。

#### `flowops-executor-client` 门禁的反向扰动

| 动作 | 结果 |
|---|---|
| 阈值初写 0.75 | 实测 87/89 = **97.8%**，0.75 太松 → 提到 **0.90**（留 ~8pt 余量） |
| 扰动 0.90 → 0.999 | ✅ `Rule violated for bundle flowops-executor-client: lines covered ratio is 0.977, but expected minimum is 0.999` → **BUILD FAILURE** |
| 还原 0.90 | 回绿 |

顺带记下这道门禁**此前装不上的因果链**：模块零测试 → 不产生 `jacoco.exec` →
`report` goal 直接 skip（日志 `Skipping JaCoCo execution due to missing execution data file`）→
`check` 连**数据源**都没有，无论阈值写多少都不会拦。**"门禁存在"和"门禁在工作"是两件事**，
只有先有测试、再用扰动证明它会拦，才能说这道门禁真的生效。

## 6. 复跑命令（本地）

```bash
# 后端：全量测试 + 6 道覆盖率门禁（common / domain / executor-client / server×2 / scheduler）
JAVA_HOME=<jdk-21> PATH=<maven-3.9.x>/bin:$PATH mvn -o -B -ntp clean verify

# 前端：三件套
cd frontend && pnpm lint && pnpm test && pnpm build
```

> 门禁清单：`flowops-common` 收窄三包 0.85 · `flowops-domain` 0.55 · `flowops-server` 逻辑层 0.55 + 模块地板 0.30 ·
> `flowops-scheduler` 0.75/0.65 · **`flowops-executor-client` 0.90（本轮新增）**。
> **`-o`（离线）不是可选项**：默认生命周期插件已钉版本，但一旦有新依赖未落本地仓库，离线会直接失败 ——
> 那正是"构建可复现"的代价，别为了让它过就摘掉 `-o`。

> 环境注意：本机 `PATH` 上的 Maven 3.6.3 / `JAVA_HOME` 指向的 jdk-11 会与 Java 21 编译不兼容（`<release>21</release>`）。可用组合为 **jdk-21 + Maven 3.9.9**。

## 7. M3 剩余范围与建议顺序

| 序 | 块 | 关键约束（来自 docs） |
|---|---|---|
| 1 | ~~算子试运行（选节点 + 实时日志 + 退出码 + `DRYRUN_OPERATOR`）~~ | ✅ **已完成**（§1.4）：SSE 四类帧 + `plan`/`run` 线程边界 + 双重脱敏 + 另存默认值；**O-17 一并收口**（`SshExecutorClientTest` 14 例 + 模块门禁 0.90）。新增偏离 O-32~O-37 |
| 2 | ~~工作流 CRUD + 版本化~~ | ✅ **已完成**（§1.2）；O-21 也随之以"构建期一致性测试"的口径闭环（§5-9） |
| 3 | 自研 SVG 画布编辑器（**D-14**） | 止损线：**超 10 人日即降级 LogicFlow**；机制注释按 D-26 第 4 条 |
| 4 | ~~DAG 校验规则接到接口并把 42213/42214/42218 回填~~ | ✅ **已完成**：保存草稿跑结构子集、发布跑全量，三码分流见 `WorkflowVersionServiceTest` |
| 5 | ~~六层变量覆盖链解析器~~ | ✅ **已完成**（§1.3）：值侧六层覆盖链 + 溯源 + 脱敏；规则 4（可达性）此前已在 `DagValidator` 落地 |
| 6 | ~~触发器 CRON（42216）+ 时间窗（42217）~~ | ✅ **已完成**（§1.3）：CRUD 6 端点 + cron-preview + 停用联动；42216/42217 纯函数三分流。**注**：调度侧的 fire 推进 / catch-up（docs/06 §11.2）属 M4，本轮只做"配置进得来、下次时间算得出" |
| 7 | 前端 6 页（算子列表/详情/版本 + 工作流列表/编辑器/详情） | 画布页单独排期 |

> **下一步建议**：**7（前端 6 页）** —— 后端主干已全部就绪（算子的列表/详情/版本、工作流的列表/编辑器/详情
> 六组接口与 DTO 都已实测），前端只剩页面本身。**画布编辑器（3）** 是其中风险最高的一块，建议在其余
> 5 页之后单独排期，并预留 D-14 的止损动作（超 10 人日降级 LogicFlow）。

---

**M3 第二~第五切片一句话总结**：工作流 CRUD + 版本化、DAG 校验接口化（三码分流）、六层变量覆盖链、
触发器 CRUD、算子试运行（SSE + 双重脱敏）五块后端主干已落地并实测（**518 用例**，
server 逻辑层覆盖 68.7% → **81.0%**，覆盖率门禁 5 道 → **6 道**）。

更有价值的是**顺带修掉的六个真缺陷**，它们都属于同一类"**本地恰好没事、换个执行路径就出事**"：

1. `workflow_version` 被登记到数据权限表却**没有 `project_id` 列** → 非管理员查询必 500（§5-8）；
2. `ws.start_command` 引用了**不属于该表的列** → 调度器每 tick 必失败（§5-9）；
3. 一致性测试自身只认 `file:` 协议 → 在 `mvn verify` 下**静默少扫 90% 的 XML**（§5-9 尾部）；
4. `trigger` 是 **PG 保留字**，DDL 与 XML 从未加引号 → 真实 PG 环境一来就全炸（§5-10）；
5. `Map.copyOf` 的迭代顺序**跨 JVM 随机** → 一条断言在 `mvn test` 绿、在 `clean verify` 红（§5-11）；
6. 一致性测试新加的正则漏了 `(?i)` → 扰动时"少扫一条"却被报成另一条错（§5-12）。

前两个是"**被 mock 掩盖**"的：单测把 Mapper 整个 mock 掉，SQL 从未真的打过库。
处理方式不是"下次注意"，而是各加一条 **DDL 一致性测试**，把这类错误整体提前到构建期。
第 3~6 条是同一主题的**四次复发**（更早两次是 §5-2 的 exclude 双语义、§5-6 的动态 attach）：
**同一个配置/代码存在两个语义域，而只验证了其中一个** —— 第 5 条的第二个语义域是**运行时**
（JVM 启动时的随机 SALT），这一条尤其值得记住：**"测试通过了"与"测试稳定通过"是两回事**。

---

**第一切片（算子域）总结（留档）**：算子域后端全链（含两条最容易做错的闸门——42211 引用闸门与 42210 错误聚合）已落地并实测；同时把 M2 留下的 `O-14` 覆盖率门禁**真正闭环**——4 个模块 5 道门禁全绿、经反向扰动验证会拦，并在补门禁的过程中抓出并修掉了一个 M2 遗留的配置缺陷（`*ConverterImpl` 从未被排除）和一处新代码零覆盖。

收尾阶段（用户要求"通过 API 自查 CI 失败并修复"）又挖出并修掉了第三个**真缺陷**：整套测试依赖 JDK 运行期 dynamic attach 来初始化 Mockito，在 CI 新 runner 镜像上必然失败——先前被我误判为"偶发"，取到 CI 日志后推翻并改为启动期 `-javaagent`（§5-6）；顺带把从未钉过版本的默认生命周期插件一并钉死。**这一轮的共同主题是"消除隐式的环境依赖"**：`*ConverterImpl` 的 exclude 模式、MapStruct 生成物、动态 attach、插件版本解析，四个都是"本地恰好没事、环境一换就出事"的同一类问题。
