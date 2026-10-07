-- =====================================================================
-- FlowOps V1 · 基础表（域 0~3，docs/05-数据模型设计.md §3 全量 DDL 唯一真源）
-- 约定：所有业务表带 created_at/created_by/updated_at/updated_by/version/deleted
--       公共六列（§1.4）；枚举 varchar + CHECK；时间一律 timestamptz。
-- 分区表 task_log 不在本文件（V2__partition_task_log.sql），
-- 治理域表在 V3__governance.sql，种子数据在 V4/V5。
-- =====================================================================

-- ── 域 0：租户与用户 ─────────────────────────────────────────────

CREATE TABLE tenant (
    id          bigserial PRIMARY KEY,
    tenant_id   varchar(32)  NOT NULL,                 -- TENANT-0001
    tenant_name varchar(128) NOT NULL,
    status      varchar(32)  NOT NULL DEFAULT 'ENABLED'
                CHECK (status IN ('ENABLED','DISABLED','ARCHIVED')),
    admin_user_id bigint,                                -- FK 在 app_user 建表后补（§3.1 循环引用）
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64),
    version    integer NOT NULL DEFAULT 0,
    deleted    boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_tenant_tenant_id ON tenant(tenant_id);
CREATE UNIQUE INDEX uk_tenant_name ON tenant(tenant_name) WHERE deleted = false;

CREATE TABLE app_user (
    id           bigserial PRIMARY KEY,
    user_id      varchar(32)  NOT NULL,
    username     varchar(64)  NOT NULL,
    display_name varchar(64)  NOT NULL,
    password_hash varchar(128) NOT NULL,               -- BCrypt
    tenant_id    bigint       REFERENCES tenant(id),   -- 可空：平台级用户
    email        varchar(128),
    phone        varchar(32),
    status       varchar(32)  NOT NULL DEFAULT 'ENABLED'
                 CHECK (status IN ('ENABLED','DISABLED','LOCKED')),
    -- 登录锁定（CONTRACT §1：连续 5 次锁定 15 分钟）
    login_fail_count   integer     NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    last_login_at      timestamptz,
    last_login_ip      varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64),
    version    integer NOT NULL DEFAULT 0,
    deleted    boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_app_user_username ON app_user(username);
CREATE INDEX idx_app_user_tenant ON app_user(tenant_id) WHERE deleted = false;
CREATE INDEX idx_app_user_locked ON app_user(locked_until) WHERE locked_until IS NOT NULL;

ALTER TABLE tenant
    ADD CONSTRAINT fk_tenant_admin_user FOREIGN KEY (admin_user_id) REFERENCES app_user(id);

-- ── 角色 / 权限点 / 绑定（6 角色 + 49 权限点，docs/07 §5.2）──────

CREATE TABLE role (
    id          bigserial PRIMARY KEY,
    role_code   varchar(64)  NOT NULL,                 -- PLATFORM_ADMIN / OPS / ...
    role_name   varchar(64)  NOT NULL,
    scope_type  varchar(32)  NOT NULL DEFAULT 'ALL'
                CHECK (scope_type IN ('ALL','TENANT','PROJECT','CLUSTER','SELF')),
    builtin     boolean      NOT NULL DEFAULT true,
    description text,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted    boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_role_code ON role(role_code) WHERE deleted = false;

CREATE TABLE permission (
    id         bigserial PRIMARY KEY,
    perm_code  varchar(128) NOT NULL,                  -- schedule:task:stop
    perm_name  varchar(128) NOT NULL,
    module     varchar(64)  NOT NULL,                  -- 所属域
    action     varchar(32)  NOT NULL,                  -- read/write/delete/publish/...
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_permission_code ON permission(perm_code);

CREATE TABLE role_permission (
    role_id       bigint NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_id bigint NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- 用户-角色（可带数据范围限定）
CREATE TABLE user_role (
    user_id     bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role_id     bigint NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    scope_ids   bigint[],
    granted_at  timestamptz NOT NULL DEFAULT now(),
    granted_by  varchar(64),
    PRIMARY KEY (user_id, role_id)
);

-- ── 域：项目空间（多租户核心）──────────────────────────────────

CREATE TABLE project (
    id        bigserial PRIMARY KEY,
    project_id varchar(32)  NOT NULL,                  -- PRJ-0001
    project_name varchar(128) NOT NULL,
    tenant_id bigint NOT NULL REFERENCES tenant(id) ON DELETE RESTRICT,
    description text,
    status    varchar(32) NOT NULL DEFAULT 'ENABLED'
              CHECK (status IN ('ENABLED','DISABLED','ARCHIVED')),
    -- 资源额度（PRD §11.4）
    max_concurrent_tasks integer NOT NULL DEFAULT 5,
    max_waiting_tasks    integer NOT NULL DEFAULT 50,
    -- 项目默认参数（PRD §12.5 继承链第 2 层）
    default_params jsonb NOT NULL DEFAULT '{}'::jsonb,
    default_timeout_seconds integer,
    default_retry_count     integer,
    default_failure_strategy varchar(32),
    owner_user_id bigint REFERENCES app_user(id),      -- 负责人（不可移除，CONTRACT §2）
    stat_workflow_count integer NOT NULL DEFAULT 0,
    stat_task_count     integer NOT NULL DEFAULT 0,
    stat_member_count   integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_project_project_id ON project(project_id);
CREATE UNIQUE INDEX uk_project_tenant_name ON project(tenant_id, project_name) WHERE deleted = false;
CREATE INDEX idx_project_tenant_status ON project(tenant_id, status) WHERE deleted = false;

-- R2：项目成员（M:N，携带项目角色）
CREATE TABLE project_member (
    project_id bigint NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id    bigint NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    project_role varchar(64) NOT NULL,
    joined_at  timestamptz NOT NULL DEFAULT now(),
    added_by   varchar(64),
    PRIMARY KEY (project_id, user_id)
);
CREATE INDEX idx_project_member_user ON project_member(user_id);

-- ── 域：资产（集群 / 节点 / 队列 / 凭据 / 算子）────────────────

CREATE TABLE cluster (
    id         bigserial PRIMARY KEY,
    cluster_id varchar(32)  NOT NULL,                  -- CL-0001
    cluster_name varchar(128) NOT NULL,
    cluster_type varchar(64)  NOT NULL DEFAULT 'GENERAL',
    status     varchar(32) NOT NULL DEFAULT 'NORMAL'
               CHECK (status IN ('NORMAL','PARTIAL_ABNORMAL','UNAVAILABLE','MAINTENANCE')),
    cpu_total  numeric(10,2) NOT NULL DEFAULT 0,
    gpu_total  numeric(10,2) NOT NULL DEFAULT 0,
    memory_total bigint NOT NULL DEFAULT 0,            -- MB
    disk_total   bigint NOT NULL DEFAULT 0,            -- MB
    node_total  integer NOT NULL DEFAULT 0,
    node_online integer NOT NULL DEFAULT 0,
    node_offline integer NOT NULL DEFAULT 0,
    node_idle   integer NOT NULL DEFAULT 0,
    running_task_count integer NOT NULL DEFAULT 0,
    pending_task_count integer NOT NULL DEFAULT 0,
    history_task_count integer NOT NULL DEFAULT 0,
    last_heartbeat_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_cluster_cluster_id ON cluster(cluster_id);
CREATE UNIQUE INDEX uk_cluster_name ON cluster(cluster_name) WHERE deleted = false;

-- R3：项目可用集群（M:N）
CREATE TABLE project_cluster (
    project_id bigint NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    cluster_id bigint NOT NULL REFERENCES cluster(id) ON DELETE RESTRICT,
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, cluster_id)
);

CREATE TABLE queue (
    id         bigserial PRIMARY KEY,
    queue_id   varchar(32)  NOT NULL,
    queue_name varchar(128) NOT NULL,
    cluster_id bigint NOT NULL REFERENCES cluster(id) ON DELETE RESTRICT,
    status     varchar(32) NOT NULL DEFAULT 'ENABLED'
               CHECK (status IN ('ENABLED','DISABLED')),
    max_concurrent_tasks integer NOT NULL DEFAULT 3,
    max_waiting_tasks    integer NOT NULL DEFAULT 50,
    default_priority     integer NOT NULL DEFAULT 0,
    allow_jump_queue     boolean NOT NULL DEFAULT false,
    wait_timeout_seconds integer NOT NULL DEFAULT 3600,   -- 超时→调度失败（PRD §12.2-6）
    waiting_task_count   integer NOT NULL DEFAULT 0,      -- 聚合缓存
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_queue_queue_id ON queue(queue_id);
CREATE UNIQUE INDEX uk_queue_cluster_name ON queue(cluster_id, queue_name) WHERE deleted = false;

-- R4：队列可用项目范围（M:N）
CREATE TABLE queue_project_scope (
    queue_id   bigint NOT NULL REFERENCES queue(id) ON DELETE CASCADE,
    project_id bigint NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    PRIMARY KEY (queue_id, project_id)
);

-- 凭据（最敏感，secret 密文存储；明文永不出网，docs/07 §6.2）
CREATE TABLE credential (
    id         bigserial PRIMARY KEY,
    credential_id varchar(32) NOT NULL,                -- CR-0001
    credential_name varchar(128) NOT NULL,
    credential_type varchar(32) NOT NULL
                 CHECK (credential_type IN ('SSH_KEY','USER_PASSWORD','WINRM','TOKEN')),
    username   varchar(128),
    -- 密文 AES-256-GCM；主密钥在环境变量/KMS，永不入库（03 §4.1 / M-06）
    secret_encrypted text NOT NULL,
    secret_fingerprint varchar(64) NOT NULL,           -- SHA-256，展示后 4 位
    project_id bigint REFERENCES project(id) ON DELETE RESTRICT,
    ref_count  integer NOT NULL DEFAULT 0,             -- 引用计数（>0 禁删，PRD §7.2）
    status     varchar(32) NOT NULL DEFAULT 'VALID'
               CHECK (status IN ('VALID','EXPIRING','EXPIRED','REVOKED')),
    last_rotated_at timestamptz,
    expire_at  timestamptz,
    description text,
    creator    varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_credential_ref_count CHECK (ref_count >= 0)
);
CREATE UNIQUE INDEX uk_credential_credential_id ON credential(credential_id);
CREATE UNIQUE INDEX uk_credential_name ON credential(credential_name)
    WHERE deleted = false AND project_id IS NULL;
CREATE UNIQUE INDEX uk_credential_project_name ON credential(project_id, credential_name)
    WHERE deleted = false AND project_id IS NOT NULL;
CREATE INDEX idx_credential_status_expire ON credential(status, expire_at)
    WHERE deleted = false;

-- 执行节点（术语：executorNode ≠ step，docs/00 §4）
CREATE TABLE executor_node (
    id         bigserial PRIMARY KEY,
    executor_node_id varchar(32) NOT NULL,
    executor_node_name varchar(128) NOT NULL,
    cluster_id bigint NOT NULL REFERENCES cluster(id) ON DELETE RESTRICT,
    ip         varchar(64) NOT NULL,
    os_type    varchar(32) NOT NULL
               CHECK (os_type IN ('LINUX','WINDOWS')),
    connect_type varchar(32) NOT NULL DEFAULT 'SSH'
               CHECK (connect_type IN ('SSH','WINRM','AGENT')),
    -- R6：凭据引用（ref_count>0 禁删 + RESTRICT 双保险）
    credential_ref_id bigint REFERENCES credential(id) ON DELETE RESTRICT,
    tags       text[] NOT NULL DEFAULT '{}',           -- 标签约束调度（PRD §12.1）
    online_status varchar(32) NOT NULL DEFAULT 'UNKNOWN'
               CHECK (online_status IN ('ONLINE','OFFLINE','UNKNOWN')),
    cpu_total    numeric(10,2) NOT NULL DEFAULT 0,
    cpu_used     numeric(10,2) NOT NULL DEFAULT 0,
    gpu_total    numeric(10,2) NOT NULL DEFAULT 0,
    gpu_used     numeric(10,2) NOT NULL DEFAULT 0,
    memory_total bigint NOT NULL DEFAULT 0,
    memory_used  bigint NOT NULL DEFAULT 0,
    disk_total   bigint NOT NULL DEFAULT 0,
    disk_used    bigint NOT NULL DEFAULT 0,
    running_task_count integer NOT NULL DEFAULT 0,
    -- v3：节点最大并发步骤数（06 §5.1 预留账本硬上限）；NULL=不限制
    max_concurrent_steps integer,
    enabled    boolean NOT NULL DEFAULT true,
    last_heartbeat_at timestamptz,
    heartbeat_miss_count integer NOT NULL DEFAULT 0,
    last_allocated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_node_executor_node_id ON executor_node(executor_node_id);
CREATE UNIQUE INDEX uk_node_cluster_ip ON executor_node(cluster_id, ip) WHERE deleted = false;
CREATE INDEX idx_node_cluster_online ON executor_node(cluster_id, online_status) WHERE deleted = false;
CREATE INDEX idx_node_tags ON executor_node USING gin(tags);
CREATE INDEX idx_node_heartbeat ON executor_node(last_heartbeat_at)
    WHERE enabled = true AND deleted = false;
CREATE INDEX idx_node_credential ON executor_node(credential_ref_id)
    WHERE credential_ref_id IS NOT NULL;
-- ⚠️ cpu_used 等"实际用量"只用于资源画像与偏差告警，不参与准入判定（D-22 预留账本）

-- ── 算子 ──────────────────────────────────────────────────────
CREATE TABLE operator (
    id          bigserial PRIMARY KEY,
    operator_id varchar(32) NOT NULL,                  -- OP-0001
    operator_name varchar(128) NOT NULL,
    operator_type varchar(32) NOT NULL
                  CHECK (operator_type IN ('JAR','PYTHON','SHELL','BAT','EXE','CUSTOM')),
    project_id  bigint NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    description text,
    status      varchar(32) NOT NULL DEFAULT 'ENABLED'
                CHECK (status IN ('ENABLED','DISABLED')),
    latest_version varchar(32),
    version_count  integer NOT NULL DEFAULT 0,
    creator     varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_operator_operator_id ON operator(operator_id);
CREATE UNIQUE INDEX uk_operator_project_name ON operator(project_id, operator_name) WHERE deleted = false;
CREATE INDEX idx_operator_project_status ON operator(project_id, status) WHERE deleted = false;

CREATE TABLE operator_version (
    id          bigserial PRIMARY KEY,
    version_id  varchar(32) NOT NULL,
    operator_id bigint NOT NULL REFERENCES operator(id) ON DELETE RESTRICT,
    version_no  varchar(32) NOT NULL,                  -- v1 / v2
    description text,
    file_name      varchar(255),
    file_size      bigint,
    file_checksum  varchar(64),                        -- SHA-256（服务端计算）
    file_path      varchar(512),
    os_type        varchar(32) CHECK (os_type IN ('LINUX','WINDOWS')),
    start_command  text,
    work_dir       varchar(512),
    env_vars       jsonb NOT NULL DEFAULT '[]'::jsonb,
    success_codes  integer[] NOT NULL DEFAULT '{0}',
    -- 默认值（继承链第 4 层，PRD §12.5）
    default_timeout_seconds integer,
    default_retry_count     integer,
    default_retry_interval_seconds integer,
    default_resource jsonb NOT NULL DEFAULT '{}'::jsonb,
    log_tail_lines integer,
    log_max_bytes  bigint NOT NULL DEFAULT 104857600,  -- 100MB
    publish_status varchar(32) NOT NULL DEFAULT 'DRAFT'
                   CHECK (publish_status IN ('DRAFT','PUBLISHED','OFFLINE')),
    is_default_version boolean NOT NULL DEFAULT false,
    publisher    varchar(64),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_ov_version_id ON operator_version(version_id);
CREATE UNIQUE INDEX uk_ov_operator_version_no ON operator_version(operator_id, version_no)
    WHERE deleted = false;
CREATE UNIQUE INDEX uk_ov_default ON operator_version(operator_id)
    WHERE is_default_version = true AND deleted = false;
CREATE INDEX idx_ov_publish_status ON operator_version(publish_status) WHERE deleted = false;

-- R9：参数定义（随版本快照，发布后禁改）
CREATE TABLE operator_param_def (
    id bigserial PRIMARY KEY,
    operator_version_id bigint NOT NULL REFERENCES operator_version(id) ON DELETE CASCADE,
    seq  integer NOT NULL DEFAULT 0,
    name varchar(128) NOT NULL,
    param_key varchar(128) NOT NULL,
    param_type varchar(32) NOT NULL
               CHECK (param_type IN ('TEXT','NUMBER','BOOLEAN','SINGLE','DATETIME')),
    required boolean NOT NULL DEFAULT false,
    default_value text,
    rule text,
    help text,
    runtime_overridable boolean NOT NULL DEFAULT true,
    sensitive boolean NOT NULL DEFAULT false,          -- 敏感参数：全链路脱敏（PRD §13.3-2）
    options jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_param_def_key ON operator_param_def(operator_version_id, param_key);
CREATE INDEX idx_param_def_version ON operator_param_def(operator_version_id, seq);

-- R10：输出声明
CREATE TABLE operator_output_decl (
    id bigserial PRIMARY KEY,
    operator_version_id bigint NOT NULL REFERENCES operator_version(id) ON DELETE CASCADE,
    seq integer NOT NULL DEFAULT 0,
    var_name varchar(128) NOT NULL,
    extract_mode varchar(32) NOT NULL
                 CHECK (extract_mode IN ('REGEX','FILE')),
    expression text NOT NULL,
    value_type varchar(32) NOT NULL DEFAULT 'TEXT',
    example_value text,
    description text,
    required boolean NOT NULL DEFAULT false,           -- 必填输出未产出 → 步骤失败（PRD §10.0.2）
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_output_decl_name ON operator_output_decl(operator_version_id, var_name);
CREATE INDEX idx_output_decl_version ON operator_output_decl(operator_version_id, seq);

-- ── 域：编排（工作流 / 版本 / 步骤 / 边 / 触发器）──────────────

CREATE TABLE workflow (
    id          bigserial PRIMARY KEY,
    workflow_id varchar(32) NOT NULL,                  -- WF-0001
    workflow_name varchar(128) NOT NULL,
    project_id  bigint NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    status      varchar(32) NOT NULL DEFAULT 'DRAFT'
                CHECK (status IN ('DRAFT','PUBLISHED','DISABLED','ARCHIVED')),
    current_version_id bigint,                         -- FK 在 workflow_version 建表后补（循环引用）
    has_draft_changes boolean NOT NULL DEFAULT false,
    concurrency_policy varchar(32) NOT NULL DEFAULT 'FORBID'
                CHECK (concurrency_policy IN ('FORBID','ALLOW','QUEUE')),
    max_parallel_runs  integer NOT NULL DEFAULT 1,
    cluster_affinity_enabled boolean NOT NULL DEFAULT false,
    -- 工作流级默认值（继承链第 3 层）
    default_timeout_seconds integer,
    default_retry_count     integer,
    default_retry_interval_seconds integer,
    default_failure_strategy varchar(32),
    notify_receivers jsonb NOT NULL DEFAULT '[]'::jsonb,
    creator     varchar(64),
    last_run_status varchar(32),
    last_run_at     timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_workflow_workflow_id ON workflow(workflow_id);
CREATE UNIQUE INDEX uk_workflow_project_name ON workflow(project_id, workflow_name) WHERE deleted = false;
CREATE INDEX idx_workflow_project_status ON workflow(project_id, status) WHERE deleted = false;
CREATE INDEX idx_workflow_last_run ON workflow(last_run_at DESC NULLS LAST) WHERE deleted = false;

-- 版本快照：发布后不可改不可删（PRD §7.2 硬约束，D-11）
CREATE TABLE workflow_version (
    id          bigserial PRIMARY KEY,
    version_id  varchar(32) NOT NULL,
    workflow_id bigint NOT NULL REFERENCES workflow(id) ON DELETE RESTRICT,
    version_no  varchar(32) NOT NULL,                  -- v1 / v2
    dag_definition jsonb NOT NULL DEFAULT '{"steps":[],"edges":[]}'::jsonb,
    workflow_params jsonb NOT NULL DEFAULT '[]'::jsonb,
    trigger_config jsonb NOT NULL DEFAULT '[]'::jsonb,
    step_count  integer NOT NULL DEFAULT 0,
    publish_status varchar(32) NOT NULL DEFAULT 'DRAFT'
                   CHECK (publish_status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    publisher   varchar(64),
    published_at timestamptz,
    canvas_width  integer,
    canvas_height integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_wv_version_id ON workflow_version(version_id);
CREATE UNIQUE INDEX uk_wv_workflow_no ON workflow_version(workflow_id, version_no) WHERE deleted = false;
CREATE INDEX idx_wv_workflow_status ON workflow_version(workflow_id, publish_status)
    WHERE deleted = false;

ALTER TABLE workflow
    ADD CONSTRAINT fk_workflow_current_version
    FOREIGN KEY (current_version_id) REFERENCES workflow_version(id) ON DELETE RESTRICT;

-- R12：步骤（版本快照的一部分）
CREATE TABLE workflow_step (
    id bigserial PRIMARY KEY,
    step_id  varchar(32) NOT NULL,
    workflow_version_id bigint NOT NULL REFERENCES workflow_version(id) ON DELETE RESTRICT,
    step_name varchar(128) NOT NULL,
    step_type varchar(32) NOT NULL DEFAULT 'TASK'
              CHECK (step_type IN ('TASK','NOTE')),
    description text,
    operator_id        bigint REFERENCES operator(id) ON DELETE RESTRICT,
    operator_version_id bigint REFERENCES operator_version(id) ON DELETE RESTRICT,
    params        jsonb NOT NULL DEFAULT '{}'::jsonb,
    custom_params jsonb NOT NULL DEFAULT '{}'::jsonb,
    target_cluster_id bigint REFERENCES cluster(id) ON DELETE RESTRICT,
    target_queue_id   bigint REFERENCES queue(id) ON DELETE RESTRICT,
    os_constraint     varchar(32) CHECK (os_constraint IN ('LINUX','WINDOWS')),
    tag_constraint    text[] NOT NULL DEFAULT '{}',
    cpu    numeric(10,2), gpu numeric(10,2),
    memory bigint,        disk bigint,
    timeout_seconds        integer,
    retry_count            integer,
    retry_interval_seconds integer,
    failure_strategy       varchar(32) CHECK (failure_strategy IN ('TERMINATE','RETRY')),
    mutex_group varchar(128),
    pos_x numeric(10,2) NOT NULL DEFAULT 0,
    pos_y numeric(10,2) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_wstep_step_id ON workflow_step(step_id);
CREATE UNIQUE INDEX uk_wstep_version_name ON workflow_step(workflow_version_id, step_name);
CREATE INDEX idx_wstep_version ON workflow_step(workflow_version_id);
CREATE INDEX idx_wstep_operator_version ON workflow_step(operator_version_id)
    WHERE operator_version_id IS NOT NULL;

-- R14：连线（发布前环检测，规则 5）
CREATE TABLE workflow_edge (
    id bigserial PRIMARY KEY,
    edge_id varchar(32) NOT NULL,
    workflow_version_id bigint NOT NULL REFERENCES workflow_version(id) ON DELETE CASCADE,
    source_step_id bigint NOT NULL REFERENCES workflow_step(id) ON DELETE CASCADE,
    target_step_id bigint NOT NULL REFERENCES workflow_step(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_edge_no_self_loop CHECK (source_step_id <> target_step_id)
);
CREATE UNIQUE INDEX uk_wedge_edge_id ON workflow_edge(edge_id);
CREATE UNIQUE INDEX uk_wedge_pair ON workflow_edge(workflow_version_id, source_step_id, target_step_id);
CREATE INDEX idx_wedge_version ON workflow_edge(workflow_version_id);

-- R15/R16：触发器
CREATE TABLE "trigger" (
    id bigserial PRIMARY KEY,
    trigger_id varchar(32) NOT NULL,
    trigger_name varchar(128) NOT NULL,
    workflow_id bigint NOT NULL REFERENCES workflow(id) ON DELETE RESTRICT,
    trigger_type varchar(32) NOT NULL
                 CHECK (trigger_type IN ('MANUAL','CRON','API','EVENT')),
    cron_expression varchar(128),
    period_seconds  integer,                           -- 固定周期（与 cron 二选一）
    locked_version_id bigint REFERENCES workflow_version(id) ON DELETE RESTRICT,
    timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
    effective_range jsonb,                             -- {start,end}
    enabled boolean NOT NULL DEFAULT true,
    enabled_before_disable boolean,
    run_params jsonb NOT NULL DEFAULT '{}'::jsonb,
    target_queue_id bigint REFERENCES queue(id) ON DELETE RESTRICT,
    catch_up_enabled    boolean NOT NULL DEFAULT true,
    catch_up_max_times  integer NOT NULL DEFAULT 3,
    next_fire_time  timestamptz,
    last_fire_time  timestamptz,
    last_fire_status varchar(32),
    last_fire_task_id bigint,                          -- FK 在 task 建表后补（循环引用）
    fail_notify jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_trigger_trigger_id ON "trigger"(trigger_id);
CREATE UNIQUE INDEX uk_trigger_workflow_name ON "trigger"(workflow_id, trigger_name) WHERE deleted = false;
-- ⭐ 调度器扫描索引：找"已启用 + 到期"
CREATE INDEX idx_trigger_next_fire ON "trigger"(next_fire_time)
    WHERE enabled = true AND deleted = false;

-- R20 前置：回填批次（task.backfill_batch_id 引用）
CREATE TABLE backfill_batch (
    id bigserial PRIMARY KEY,
    batch_id varchar(32) NOT NULL,                     -- BF-0001
    batch_name varchar(128),
    workflow_id bigint NOT NULL REFERENCES workflow(id) ON DELETE RESTRICT,
    workflow_version_id bigint REFERENCES workflow_version(id) ON DELETE RESTRICT,
    project_id bigint NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    biz_from date NOT NULL,
    biz_to   date NOT NULL,
    concurrency integer NOT NULL DEFAULT 1,
    sequential  boolean NOT NULL DEFAULT true,
    eff_concurrency integer NOT NULL DEFAULT 1,        -- 收敛后实际并发（CONTRACT §8）
    status varchar(32) NOT NULL DEFAULT 'RUNNING'
           CHECK (status IN ('RUNNING','PAUSED','DONE','FAILED','CANCELLED')),
    total   integer NOT NULL DEFAULT 0,
    success_count integer NOT NULL DEFAULT 0,
    failed_count  integer NOT NULL DEFAULT 0,
    waiting_count integer NOT NULL DEFAULT 0,
    skipped_dates date[] NOT NULL DEFAULT '{}',
    preview_hash varchar(64),                          -- 防跳过预览直提（CONTRACT §8 闸门）
    submitted_at timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    duration_ms  bigint,
    submitter    varchar(64),
    overrides jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT ck_backfill_range CHECK (biz_to >= biz_from)
);
CREATE UNIQUE INDEX uk_backfill_batch_id ON backfill_batch(batch_id);
CREATE INDEX idx_backfill_workflow ON backfill_batch(workflow_id, submitted_at DESC)
    WHERE deleted = false;
CREATE INDEX idx_backfill_status ON backfill_batch(status) WHERE deleted = false;

-- ── 域：任务与执行（最核心）────────────────────────────────────

CREATE TABLE task (
    id         bigserial PRIMARY KEY,
    task_id    varchar(32) NOT NULL,                   -- TASK-20260921-0001
    workflow_id bigint NOT NULL REFERENCES workflow(id) ON DELETE RESTRICT,
    workflow_name varchar(128) NOT NULL,               -- 快照冗余（避免 join）
    workflow_version_id bigint NOT NULL REFERENCES workflow_version(id) ON DELETE RESTRICT,
    workflow_version varchar(32) NOT NULL,             -- 快照冗余
    project_id bigint NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    trigger_type varchar(32) NOT NULL
                 CHECK (trigger_type IN ('MANUAL','CRON','API','EVENT','BACKFILL')),
    trigger_id   bigint REFERENCES "trigger"(id) ON DELETE SET NULL,
    backfill_batch_id bigint REFERENCES backfill_batch(id) ON DELETE SET NULL,
    biz_date   date,                                   -- 业务日期（回填/定时场景）
    submitter  varchar(64),
    cluster_id bigint REFERENCES cluster(id) ON DELETE RESTRICT,
    cluster_name varchar(128),
    queue_id   bigint REFERENCES queue(id) ON DELETE RESTRICT,
    queue_name varchar(128),
    priority   integer NOT NULL DEFAULT 0,             -- 越大越优先（PRD §12.2；0~100，E-07）
    status     varchar(32) NOT NULL DEFAULT 'PENDING'
               CHECK (status IN ('PENDING','SCHEDULING','RUNNING','STOPPING','SUCCESS',
                                 'FAILED','STOPPED','TIMEOUT','PARTIAL')),   -- 9 态（M-08）
    current_step_name varchar(128),
    current_step_index integer,
    step_total   integer NOT NULL DEFAULT 0,
    finished_steps integer NOT NULL DEFAULT 0,
    submit_at  timestamptz NOT NULL DEFAULT now(),
    start_time timestamptz,
    end_time   timestamptz,
    duration_ms bigint,
    fail_reason text,
    stopped_by  varchar(64),
    stop_reason text,
    -- 运行时变量快照（6 层解析后的最终值，PRD §10.0.4；敏感值不落原文，M-07）
    variable_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    diagnosis_info jsonb,
    enqueue_seq bigint,                                -- 入队序号（Redis INCR，M-09）
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_task_task_id ON task(task_id);
CREATE INDEX idx_task_project_status_submit ON task(project_id, status, submit_at DESC)
    WHERE deleted = false;
CREATE INDEX idx_task_workflow_submit ON task(workflow_id, submit_at DESC) WHERE deleted = false;
CREATE INDEX idx_task_status_submit ON task(status, submit_at DESC) WHERE deleted = false;
CREATE INDEX idx_task_cluster_status ON task(cluster_id, status) WHERE deleted = false;
CREATE INDEX idx_task_queue_status ON task(queue_id, status) WHERE deleted = false;
CREATE INDEX idx_task_submitter ON task(submitter, submit_at DESC) WHERE deleted = false;
CREATE INDEX idx_task_biz_date ON task(workflow_id, biz_date) WHERE deleted = false;
CREATE INDEX idx_task_backfill ON task(backfill_batch_id) WHERE backfill_batch_id IS NOT NULL;
-- ⭐ 调度器恢复：找活跃任务（含 STOPPING，docs/06 §15.1）
CREATE INDEX idx_task_active ON task(status)
    WHERE status IN ('PENDING','SCHEDULING','RUNNING','STOPPING') AND deleted = false;
-- 冲突检测（回填 ±30 分钟窗口，CONTRACT §8）
CREATE INDEX idx_task_conflict ON task(workflow_id, biz_date, submit_at)
    WHERE deleted = false;

ALTER TABLE "trigger"
    ADD CONSTRAINT fk_trigger_last_task FOREIGN KEY (last_fire_task_id) REFERENCES task(id) ON DELETE SET NULL;

-- 步骤实例（11 态，写入量最大）
CREATE TABLE task_step (
    id bigserial PRIMARY KEY,
    step_instance_id varchar(32) NOT NULL,
    task_id    bigint NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    step_id    bigint REFERENCES workflow_step(id) ON DELETE RESTRICT,
    step_name  varchar(128) NOT NULL,
    step_index integer NOT NULL DEFAULT 0,
    status     varchar(32) NOT NULL DEFAULT 'NOT_STARTED'
               CHECK (status IN ('NOT_STARTED','WAITING_DEPENDENCY','WAITING_RESOURCE',
                                 'SCHEDULING','RUNNING','SUCCESS','FAILED','RETRYING',
                                 'SKIPPED','STOPPED','TIMEOUT')),                   -- 11 态
    cluster_id bigint REFERENCES cluster(id) ON DELETE RESTRICT,
    machine_ip varchar(64),
    operator_id bigint REFERENCES operator(id) ON DELETE RESTRICT,
    operator_version_id bigint REFERENCES operator_version(id) ON DELETE RESTRICT,
    start_time timestamptz, end_time timestamptz, duration_ms bigint,
    exit_code  integer,
    fail_reason text,
    -- v3：结构化失败原因（08 §3.5 九值枚举 + 上下文）
    fail_reason_code   varchar(32),
    fail_reason_detail jsonb,
    retry_count  integer NOT NULL DEFAULT 0,
    max_retry_count integer,
    next_retry_at timestamptz,                         -- 重试到期（06 §9.1）
    enqueue_seq  bigint,                               -- score 组成部分，回退保留防饥饿（06 §4.2）
    stdout_log_ref varchar(255),
    stderr_log_ref varchar(255),
    log_line_count bigint NOT NULL DEFAULT 0,
    log_bytes      bigint NOT NULL DEFAULT 0,
    log_truncated  boolean NOT NULL DEFAULT false,     -- 超 100MB 截断（PRD §13.1-6）
    resource_request jsonb NOT NULL DEFAULT '{}'::jsonb,
    resource_actual  jsonb,
    output_vars jsonb NOT NULL DEFAULT '{}'::jsonb,    -- 供下游 ${step.X.output.Y} 引用（D-20）
    block_reason jsonb,
    mutex_group varchar(128),
    mutex_holder boolean NOT NULL DEFAULT false,
    dispatch_token varchar(64),                        -- CAS 幂等标记（D-23）
    resolved_command text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_tstep_instance_id ON task_step(step_instance_id);
CREATE UNIQUE INDEX uk_tstep_task_step ON task_step(task_id, step_id);
CREATE INDEX idx_tstep_task ON task_step(task_id, step_index);
CREATE INDEX idx_tstep_status ON task_step(status)
    WHERE status IN ('WAITING_DEPENDENCY','WAITING_RESOURCE','SCHEDULING');
CREATE INDEX idx_tstep_running_node ON task_step(machine_ip)
    WHERE status = 'RUNNING';
CREATE INDEX idx_tstep_mutex ON task_step(mutex_group)
    WHERE mutex_holder = true AND status = 'RUNNING';
CREATE INDEX idx_tstep_timeout ON task_step(start_time)
    WHERE status = 'RUNNING';
CREATE INDEX idx_tstep_dispatch_token ON task_step(dispatch_token)
    WHERE dispatch_token IS NOT NULL;
CREATE INDEX idx_tstep_next_retry ON task_step(next_retry_at)
    WHERE status = 'RETRYING' AND next_retry_at IS NOT NULL;
CREATE INDEX idx_tstep_waiting_mutex ON task_step(mutex_group, enqueue_seq)
    WHERE status = 'WAITING_RESOURCE' AND mutex_group IS NOT NULL;
CREATE INDEX idx_tstep_fail_code ON task_step(fail_reason_code, created_at DESC)
    WHERE fail_reason_code IS NOT NULL;

-- R19：重试历史（结构化）
CREATE TABLE task_step_retry (
    id bigserial PRIMARY KEY,
    task_step_id bigint NOT NULL REFERENCES task_step(id) ON DELETE CASCADE,
    attempt_no   integer NOT NULL,
    status       varchar(32) NOT NULL,
    machine_ip   varchar(64),
    start_time   timestamptz, end_time timestamptz, duration_ms bigint,
    exit_code    integer,
    fail_reason  text,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_tsretry_attempt ON task_step_retry(task_step_id, attempt_no);

-- v3：触发点火日志（06 §11.1.1 的幂等支点，uq_trigger_fire 是唯一支点）
CREATE TABLE trigger_fire_log (
    id          bigserial PRIMARY KEY,
    trigger_id  bigint      NOT NULL REFERENCES "trigger"(id) ON DELETE CASCADE,
    fire_time   timestamptz NOT NULL,
    task_id     bigint REFERENCES task(id) ON DELETE SET NULL,
    status      varchar(16) NOT NULL
                CHECK (status IN ('FIRED','SKIPPED','FAILED')),
    skip_reason varchar(32),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_trigger_fire UNIQUE (trigger_id, fire_time)
);
CREATE INDEX idx_trigger_fire_recent ON trigger_fire_log(trigger_id, fire_time DESC);
CREATE INDEX idx_trigger_fire_orphan ON trigger_fire_log(created_at DESC)
    WHERE status = 'FIRED' AND task_id IS NULL;
