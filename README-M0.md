# FlowOps 工程实施 · M0 交付说明（工程骨架与基础设施）

> 依据：docs/09 §2「M0 · 工程骨架与基础设施」交付物清单与 DoD。
> 本文件是 M0 的自检报告：每条 DoD 的落点、验证方式、以及**当前未能验证的部分**（如实声明）。

---

## 1. 目录结构

```
schedule-platform/
├── backend/                        # Maven 多模块（docs/03 §1.1）
│   ├── pom.xml                     # 父 POM：版本锁定（docs/02 §5）
│   ├── flowops-common/             # ApiResult/ErrorCode(全表)/BizException/全局异常/TraceFilter/枚举/切面注解
│   ├── flowops-domain/             # 共享实体与 Mapper（audit_log、app_user）
│   ├── flowops-server/             # REST + 切面链 + Flyway（db/migration V1~V5）
│   ├── flowops-scheduler/          # 选主（Redisson D-21）+ tick 骨架 + 恢复自检
│   └── flowops-executor-client/    # 执行协议契约（at-least-once 幂等键，D-23）
├── frontend/                       # Vue3 + TS + Vite + Pinia + Element Plus
│   └── src/
│       ├── styles/tokens.css       # ⭐ 原型 tokens.css 整块搬运（唯一视觉真源，零修改）
│       ├── styles/base.css         # 原型组件样式搬运（M2 起逐步拆分为组件内样式）
│       ├── constants/permissions.ts# ⭐ 49 权限点单一常量源
│       ├── router/routes.ts        # 19 页路由映射（docs/04 §3.3 逐行对照）+ 侧栏导航
│       └── views/                  # 登录页（可登录）+ 17 页骨架占位 + 403/404
├── deploy/                         # docker-compose.yml + Dockerfile.backend/web + nginx.conf + .env.example
├── scripts/dev-deps.sh             # 本地一键起 PG/Redis
└── .github/workflows/ci.yml        # 后端 mvn verify + 前端 lint/test/build
```

## 2. DoD 自检（docs/09 M0 完成定义）

| # | DoD | 状态 | 落点 / 说明 |
|---|---|---|---|
| 1 | `docker compose up -d` 后 `/actuator/health` 返回 UP（含 db、redis） | ⚠️ 代码就绪，**本机未验证** | `deploy/docker-compose.yml`：pg/redis 带健康检查，server 健康检查探测 health；scheduler 2 副本等 server 迁移完成。**本机无 Docker，需在有 Docker 的环境首跑确认** |
| 2 | Flyway 迁移成功，全量表存在，分区自动创建 | ✅ 脚本就绪 / ⚠️ 未实跑 | V1 基础 28 表 + V2 task_log 分区表与 ensure 函数 + V3 治理 11 表 = **40 张表**（docs/05 全量含 v0.2d 4 表）；V4 种子（6 角色 + 49 权限点 + 管理员 + 平台配置）+ V5 告警默认规则（PRD §10.12 MVP 5 类） |
| 3 | `pnpm dev` 打开登录页，`POST /auth/login` 返回 ApiResult | ✅ 代码就绪 / ⚠️ 本机未验证 | 登录页走真实接口（vite 代理 8080）；种子账号 `admin / Admin@123`（BCrypt 哈希已预生成验证） |
| 4 | 一个「空接口」走通切面链 | ✅ 代码就绪 | `POST /platform/view-mode`：`@RequiresPermission("schedule:platform:view:ops")` → `@Audited(action="SWITCH_OPS_VIEW", targetType="USER")` 异步写 audit_log → 响应 `{code,message,data,trace_id}`。SWITCH_OPS_VIEW 属必审动作（docs/07 §7.3），target_type 取 USER（audit_log CHECK 允许） |
| 5 | CI 在 PR 上跑通 | ⚠️ 配置就绪，需仓库推送后验证 | `.github/workflows/ci.yml`：backend `mvn verify`；frontend lint + vitest（权限点防漂移 49 断言）+ vue-tsc + build |

## 3. 本机验证限制（如实声明）

开发机（Windows）工具链与 docs/02 锁定基线不匹配，以下验证**只能由 CI 或 Docker 环境完成**：

| 项 | 本机现状 | 基线要求 |
|---|---|---|
| JDK | 11 | 21（LTS） |
| Maven | 未安装 | 3.9.9 |
| Node | 12.14 | 20 LTS |
| pnpm / Docker | 未安装 | pnpm 9 / Docker |
| PostgreSQL / Redis | 未安装 | 16 / 7 |

已做的静态自检：JSON/YAML 语法解析通过；DDL 40 表计数核对通过；权限点 49 计数核对通过；种子密码 BCrypt 回验通过；`frontend/tests/permissions.spec.ts` 断言路由表 19 页全覆盖。

## 4. 首跑确认项（Runbook）

1. **`docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d`**（先 `cp deploy/.env.example deploy/.env` 并填 `FLOWOPS_CRED_MASTER_KEY`，生成：`openssl rand -base64 32`）。
2. 验证 `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`（含 db、redis）。
3. `curl -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@123"}'` → 取 `trace_id` 响应包与 token。
4. 携 token `POST /api/v1/platform/view-mode` → 观察 audit_log 表新增一行（SWITCH_OPS_VIEW）。
5. 前端 `cd frontend && pnpm install && pnpm dev` → 登录 → 侧栏按权限渲染。

**已知待首跑确认的细节**：
- 表名 `trigger` 在 PostgreSQL 中为非保留字，可直接建表（docs/05 原文口径）；如遇首跑方言问题，最小改法是迁移与 `@TableName` 同步加引号。
- docs/05 §3.6 的 `api_key`/`webhook_subscription` 原文 `created_by` 列重复出现两次（文档笔误），迁移脚本各保留一列并已注释。
- `tenant.admin_user_id`、`workflow.current_version_id`、`trigger.last_fire_task_id` 三处循环外键，按「建表后 ALTER 补 FK」处理，语义与 docs/05 一致。

## 5. 与决策日志的对应（抽查表）

| 决策 | 落点 |
|---|---|
| D-01 版本锁定 | backend/pom.xml（Spring Boot 3.3.4 / MyBatis-Plus 3.5.7 / Sa-Token 1.39.0 …） |
| D-05 snake_case | JacksonConfig（全局 SNAKE_CASE）+ 前端 casename.ts（仅 axios 边界转换） |
| D-06 Sa-Token | application.yml（token-prefix=Bearer / timeout 7d / is-concurrent=false） |
| D-08 server/scheduler 拆分 | 独立 Maven 模块，互不依赖；compose 分进程 |
| D-09 PG 真源 | scheduler 的 RecoveryService 骨架（重建派生态 M1 实现） |
| D-17 幂等 | @Idempotent + IdempotentAspect（Redis 24h，40903 冲突检测） |
| D-18/D-19 权限点/DataScope | @RequiresPermission + PermissionAspect；@DataScope 骨架（M2 落 SQL 注入） |
| D-21 选主 | LeaderElector（Redisson 锁 + 10s 抢锁重试） |
| M-06 主密钥 | CryptoKeyChecker（缺失拒绝启动） |
| 49 权限点 | V4 种子 + frontend/constants/permissions.ts + tests/permissions.spec.ts（三处同源对齐） |

## 6. 里程碑边界（M0 只做骨架）

- 调度内核各扫描器、并发/互斥/预留账本 → M1（docs/06）
- 登录锁定 5 次/15 分钟、DataScope SQL 注入、权限矩阵遍历测试 → M2
- 切面的 before/after 摘要与字段级 diff → M2 起按域补齐
- 日志 WebSocket、任务域接口 → M1/M4
- 前端 17 个占位页的交互 → M2~M5 按里程碑逐页落地
