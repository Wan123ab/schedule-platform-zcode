-- =====================================================================
-- FlowOps V3 · 治理域表（docs/05 §3.6 / §3.7）
-- 告警 / 调度决策留痕 / 审计（只增不改不删）/ v0.2d 开放接口 4 表 / 日志授权 / 平台配置
-- =====================================================================

CREATE TABLE alert_rule (
    id bigserial PRIMARY KEY,
    rule_id varchar(32) NOT NULL,                      -- AR-0001
    rule_name varchar(128) NOT NULL,
    event_type varchar(32) NOT NULL
               CHECK (event_type IN ('TASK_FAILED','TASK_TIMEOUT','STEP_RETRY_EXHAUSTED',
                                     'NODE_OFFLINE','CONCURRENCY_LIMIT','QUEUE_BACKLOG',
                                     'RESOURCE_HIGH','CREDENTIAL_EXPIRED','TRIGGER_FAIL')),
    condition jsonb NOT NULL DEFAULT '{}'::jsonb,      -- 按事件类型定义
    channels  text[] NOT NULL DEFAULT '{}',            -- EMAIL / WECOM / WEBHOOK
    receivers jsonb NOT NULL DEFAULT '[]'::jsonb,
    suppress_window_minutes integer NOT NULL DEFAULT 30,
    project_id bigint REFERENCES project(id) ON DELETE CASCADE,
    cluster_id bigint REFERENCES cluster(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT true,
    creator varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64), updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64), version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_alert_rule_id ON alert_rule(rule_id);
CREATE UNIQUE INDEX uk_alert_rule_name ON alert_rule(rule_name) WHERE deleted = false;
CREATE INDEX idx_alert_rule_event ON alert_rule(event_type) WHERE enabled = true AND deleted = false;

CREATE TABLE alert_channel (
    id bigserial PRIMARY KEY,
    channel_type varchar(32) NOT NULL UNIQUE
                 CHECK (channel_type IN ('EMAIL','WECOM','WEBHOOK','DINGTALK')),
    endpoint  varchar(512),
    secret_encrypted text,                             -- 同凭据加密策略
    enabled   boolean NOT NULL DEFAULT false,
    verified  boolean NOT NULL DEFAULT false,
    last_test_at   timestamptz,
    last_test_result varchar(255),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 0
);

CREATE TABLE alert_record (
    id bigserial PRIMARY KEY,
    alert_id varchar(32) NOT NULL,
    rule_id  bigint REFERENCES alert_rule(id) ON DELETE SET NULL,
    event_type varchar(32) NOT NULL,
    level    varchar(16) NOT NULL DEFAULT 'WARN'
             CHECK (level IN ('CRITICAL','WARN','INFO')),
    -- R20：多态引用（任务 / 执行节点），无 FK
    target_type varchar(32) NOT NULL
                CHECK (target_type IN ('TASK','TASK_STEP','EXECUTOR_NODE','QUEUE','CREDENTIAL','TRIGGER')),
    target_id   bigint NOT NULL,
    target_name varchar(255),
    project_id  bigint,
    cluster_id  bigint,
    content     text,
    channels    text[] NOT NULL DEFAULT '{}',
    deliver_result jsonb NOT NULL DEFAULT '[]'::jsonb, -- [{channel,ok,error,latencyMs}]
    result      varchar(32) NOT NULL DEFAULT 'SENT'
                CHECK (result IN ('SENT','PARTIAL','FAILED','SUPPRESSED')),
    fired_at    timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_alert_record_id ON alert_record(alert_id);
CREATE INDEX idx_alert_fired ON alert_record(fired_at DESC);
CREATE INDEX idx_alert_event_fired ON alert_record(event_type, fired_at DESC);
CREATE INDEX idx_alert_target ON alert_record(target_type, target_id);
CREATE INDEX idx_alert_suppress ON alert_record(event_type, target_id, fired_at DESC);

-- v3：调度决策留痕（DISPATCHED / DEFERRED / BLOCKED / SKIPPED，06 §14.2.1）
CREATE TABLE schedule_decision_log (
    id               bigserial PRIMARY KEY,
    trace_id         varchar(32) NOT NULL,
    task_id          bigint      NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    step_instance_id bigint,
    decided_at       timestamptz NOT NULL DEFAULT now(),
    decision         varchar(32) NOT NULL
                     CHECK (decision IN ('DISPATCHED','DEFERRED','BLOCKED','SKIPPED')),
    reason_code      varchar(64),   -- NO_MATCHING_NODE / MUTEX_GROUP / CONCURRENCY_LIMIT / ...
    input_snapshot   jsonb,         -- 判定当时的输入（脱敏后）
    chosen_node_id   bigint,
    score_detail     jsonb          -- 三级排序打分明细
);
CREATE INDEX idx_schedlog_task ON schedule_decision_log(task_id, decided_at DESC);
CREATE INDEX idx_schedlog_decision ON schedule_decision_log(decision, decided_at DESC);

-- 审计日志：只增不改不删（DB 层 REVOKE 见 docs/05 §7.4，迁移用户名落地时替换 flowops_app）
CREATE TABLE audit_log (
    id bigserial PRIMARY KEY,
    audit_id varchar(32) NOT NULL,
    operated_at timestamptz NOT NULL DEFAULT now(),
    operator    varchar(64) NOT NULL,
    operator_name varchar(64),
    target_type varchar(32) NOT NULL
                CHECK (target_type IN ('PROJECT','WORKFLOW','WORKFLOW_VERSION','OPERATOR',
                                       'OPERATOR_VERSION','CLUSTER','EXECUTOR_NODE','CREDENTIAL',
                                       'QUEUE','TASK','BACKFILL','ALERT_RULE','TRIGGER',
                                       'USER','ROLE','TENANT')),
    target_id   varchar(64),
    target_name varchar(255),
    project_id  bigint,
    action      varchar(64) NOT NULL,                  -- PUBLISH_WORKFLOW / STOP_TASK / ...
    before_summary text,
    after_summary  text,
    diff        jsonb NOT NULL DEFAULT '[]'::jsonb,    -- [{field,old,new}]（CONTRACT §10）
    result      varchar(16) NOT NULL DEFAULT 'SUCCESS'
                CHECK (result IN ('SUCCESS','FAIL','PARTIAL')),
    fail_reason text,
    source_ip   varchar(64),
    user_agent  varchar(255),
    trace_id    varchar(64) NOT NULL,                  -- D-05：线协议 trace_id
    reason      text,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_audit_audit_id ON audit_log(audit_id);
CREATE INDEX idx_audit_trace ON audit_log(trace_id);
CREATE INDEX idx_audit_operated ON audit_log(operated_at DESC);
CREATE INDEX idx_audit_operator ON audit_log(operator, operated_at DESC);
CREATE INDEX idx_audit_target ON audit_log(target_type, target_id, operated_at DESC);
CREATE INDEX idx_audit_action ON audit_log(action, operated_at DESC);
CREATE INDEX idx_audit_project ON audit_log(project_id, operated_at DESC);

-- v0.2d：API Key（开放接口鉴权，X-API-Key；明文不落库）
CREATE TABLE api_key (
    id bigserial PRIMARY KEY,
    key_id varchar(32) NOT NULL,              -- AK-0001
    key_name varchar(128) NOT NULL,
    key_hash varchar(64) NOT NULL,            -- SHA-256
    key_mask varchar(32) NOT NULL,            -- fk_****8c3f
    project_id bigint NOT NULL REFERENCES project(id),
    workflow_scope text[] NOT NULL DEFAULT '{}',   -- '{*}' = 全部；空 = 仅查询
    rate_limit_per_min integer NOT NULL DEFAULT 60,
    ip_whitelist text[] NOT NULL DEFAULT '{}',
    status varchar(16) NOT NULL DEFAULT 'ENABLED'
               CHECK (status IN ('ENABLED','DISABLED','REVOKED')),
    expire_at timestamptz,
    rotate_of bigint REFERENCES api_key(id),  -- 轮换链
    created_by varchar(64),
    last_used_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- 注：docs/05 §3.6 api_key 原文 created_by 重复出现两次（文档笔误），此处保留一列
    updated_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_api_key_hash ON api_key(key_hash) WHERE deleted = false;
CREATE INDEX idx_api_key_project ON api_key(project_id) WHERE deleted = false;

-- v0.2d：开放接口调用日志（与审计 OPENAPI_CALL 同源双写，保留 90 天）
CREATE TABLE openapi_call_log (
    id bigserial PRIMARY KEY,
    key_id bigint NOT NULL REFERENCES api_key(id),
    endpoint varchar(128) NOT NULL,
    task_id bigint REFERENCES task(id),
    http_code integer NOT NULL,
    latency_ms integer,
    trace_id varchar(32) NOT NULL,
    note text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_calllog_key_time ON openapi_call_log(key_id, created_at DESC);

-- v0.2d：Webhook 订阅（HMAC-SHA256 签名通知）
CREATE TABLE webhook_subscription (
    id bigserial PRIMARY KEY,
    sub_id varchar(32) NOT NULL,              -- WH-0001
    name varchar(128) NOT NULL,
    url text NOT NULL,
    events text[] NOT NULL,                   -- {task.succeeded, task.failed, ...}
    secret_hash varchar(64) NOT NULL,         -- whsec_ 的哈希
    project_id bigint NOT NULL REFERENCES project(id),
    status varchar(16) NOT NULL DEFAULT 'ENABLED'
               CHECK (status IN ('ENABLED','DISABLED')),
    created_by varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 0,
    deleted boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_webhook_sub_id ON webhook_subscription(sub_id) WHERE deleted = false;

-- v0.2d：Webhook 送达记录（5s/30s/300s 退避重试逐次记录）
CREATE TABLE webhook_delivery (
    id bigserial PRIMARY KEY,
    sub_id bigint NOT NULL REFERENCES webhook_subscription(id),
    event varchar(32) NOT NULL,
    task_id bigint REFERENCES task(id),
    http_code integer,
    attempt integer NOT NULL DEFAULT 1,
    result varchar(16) NOT NULL DEFAULT 'SENT'
               CHECK (result IN ('SENT','RETRY','FAILED')),
    latency_ms integer,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_delivery_sub_time ON webhook_delivery(sub_id, created_at DESC);
CREATE INDEX idx_delivery_result ON webhook_delivery(result) WHERE result != 'SENT';

-- 跨项目日志查看授权（单次有效，PRD §11.3）
CREATE TABLE log_view_grant (
    id bigserial PRIMARY KEY,
    grant_token varchar(64) NOT NULL,
    task_step_id bigint NOT NULL REFERENCES task_step(id) ON DELETE CASCADE,
    granted_to  varchar(64) NOT NULL,
    reason      text NOT NULL,
    granted_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    revoked     boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX uk_lvg_token ON log_view_grant(grant_token);
CREATE INDEX idx_lvg_active ON log_view_grant(granted_to, expires_at)
    WHERE revoked = false AND used_at IS NULL;

-- 平台配置（系统设置）
CREATE TABLE platform_config (
    id bigserial PRIMARY KEY,
    config_key   varchar(128) NOT NULL,
    config_value text,
    config_group varchar(64) NOT NULL DEFAULT 'GENERAL',
    value_type   varchar(32) NOT NULL DEFAULT 'STRING',
    description  text,
    editable     boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(64),
    version integer NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_platform_config_key ON platform_config(config_key);
