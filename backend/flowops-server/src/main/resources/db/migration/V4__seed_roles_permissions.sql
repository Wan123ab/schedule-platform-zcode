-- =====================================================================
-- FlowOps V4 · 种子数据：默认租户 + 6 角色 + 49 权限点 + 角色绑定 + 管理员 + 平台配置
-- 权限点清单唯一权威来源：docs/07 §5.2（49 点，v0.2d +2）
-- 角色清单：docs/03 §3.1（PRD §11.2）
-- =====================================================================

-- ── 默认租户（M-01：一期仅 1 个默认租户，UI 不暴露）────────────
INSERT INTO tenant (tenant_id, tenant_name, status, description, created_by)
VALUES ('TENANT-0001', '默认租户', 'ENABLED', '一期默认租户（M-01）', 'system');

-- ── 角色（6 个，docs/03 §3.1）──────────────────────────────────
INSERT INTO role (role_code, role_name, scope_type, description) VALUES
('PLATFORM_ADMIN',      '平台管理员',   'ALL',    '全平台'),
('OPS',                 '运维人员',     'CLUSTER','资产 + 任务运维，被授权集群范围'),
('PROJECT_ADMIN',       '项目管理员',   'PROJECT','本项目内全部'),
('OPERATOR_MAINTAINER', '算子维护者',   'SELF',   '本人维护的算子'),
('BUSINESS',            '业务人员',     'SELF',   '本项目内提交/查看本人任务'),
('AUDITOR',             '只读/审计',    'ALL',    '全平台只读（审计视角）');

-- ── 权限点（49 个，docs/07 §5.2 完整清单）──────────────────────
INSERT INTO permission (perm_code, perm_name, module, action) VALUES
-- 1~6 租户/项目
('schedule:tenant:write',        '租户创建/编辑/停用',            'tenant',    'write'),
('schedule:tenant:read',         '租户查看',                      'tenant',    'read'),
('schedule:project:write',       '项目创建/编辑/停用',            'project',   'write'),
('schedule:project:read',        '项目查看',                      'project',   'read'),
('schedule:project:member',      '成员与角色管理',                'project',   'member'),
('schedule:project:quota',       '资源额度与参数配置',            'project',   'quota'),
-- 7~13 集群/节点/队列
('schedule:cluster:write',       '集群创建/编辑/删除/维护',       'cluster',   'write'),
('schedule:cluster:read',        '集群查看',                      'cluster',   'read'),
('schedule:node:write',          '执行节点新增/编辑/删除/启停',   'node',      'write'),
('schedule:node:read',           '节点查看',                      'node',      'read'),
('schedule:node:test',           '节点连通性测试',                'node',      'test'),
('schedule:queue:write',         '队列配置',                      'queue',     'write'),
('schedule:queue:read',          '队列查看',                      'queue',     'read'),
-- 14~16 凭据
('schedule:credential:write',    '凭据创建/编辑/轮换/删除',       'credential','write'),
('schedule:credential:read',     '凭据列表查看（不含明文）',      'credential','read'),
('schedule:credential:rotate',   '凭据轮换',                      'credential','rotate'),
-- 17~22 算子
('schedule:operator:read',       '算子与算子版本查看',            'operator',  'read'),
('schedule:operator:write',      '算子上传/编辑',                 'operator',  'write'),
('schedule:operator:delete',     '算子删除/启停',                 'operator',  'delete'),
('schedule:operator:publish',    '算子版本新增/编辑/删除/发布',   'operator',  'publish'),
('schedule:operator:dryrun',     '算子试运行',                    'operator',  'dryrun'),
('schedule:operator:param',      '参数模板配置',                  'operator',  'param'),
-- 23~29 工作流/触发器
('schedule:workflow:read',       '工作流与版本查看',              'workflow',  'read'),
('schedule:workflow:write',      '工作流新建/编辑草稿',           'workflow',  'write'),
('schedule:workflow:publish',    '工作流发布/停用/复制',          'workflow',  'publish'),
('schedule:workflow:delete',     '工作流删除',                    'workflow',  'delete'),
('schedule:workflow:execute',    '工作流运行',                    'workflow',  'execute'),
('schedule:trigger:write',       '触发器配置',                    'trigger',   'write'),
('schedule:trigger:read',        '触发器查看',                    'trigger',   'read'),
-- 30~36 任务
('schedule:task:read',           '任务查看',                      'task',      'read'),
('schedule:task:log:raw',        '任务日志原文查看',              'task',      'log:raw'),
('schedule:task:log:grant',      '申请日志查看授权（跨项目）',    'task',      'log:grant'),
('schedule:task:stop',           '任务停止',                      'task',      'stop'),
('schedule:task:retry',          '重跑/重跑失败步骤',             'task',      'retry'),
('schedule:task:enqueue_front',  '任务插队',                      'task',      'enqueue_front'),
('schedule:task:submit',         '手工提交任务',                  'task',      'submit'),
-- 37~38 回填
('schedule:backfill:read',       '回填查看',                      'backfill',  'read'),
('schedule:backfill:write',      '回填创建/暂停/继续',            'backfill',  'write'),
-- 39~40 告警
('schedule:alert:read',          '告警规则/历史查看',             'alert',     'read'),
('schedule:alert:write',         '告警规则与渠道配置',            'alert',     'write'),
-- 41~43 审计
('schedule:audit:read',          '审计查看（全量）',              'audit',     'read'),
('schedule:audit:self',          '审计查看（仅本人操作）',        'audit',     'self'),
('schedule:audit:project',       '审计查看（本项目）',            'audit',     'project'),
-- 44~47 平台
('schedule:platform:config',     '平台全局设置',                  'platform',  'config'),
('schedule:platform:health',     '平台健康度面板',                'platform',  'health'),
('schedule:platform:migrate',    '存量迁移工具',                  'platform',  'migrate'),
('schedule:platform:view:ops',   '切换到运维视图',                'platform',  'view:ops'),
-- 48~49 开放接口（v0.2d）
('schedule:openapi:read',        'API 接入查看',                  'openapi',   'read'),
('schedule:openapi:write',       'API 接入管理',                  'openapi',   'write');

-- ── 角色-权限绑定（docs/07 §5.2「允许角色」列逐行对齐）────────

-- 平台管理员：全部 49 点
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.role_code = 'PLATFORM_ADMIN';

-- 运维人员 OPS
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.role_code = 'OPS'
  AND p.perm_code IN (
    'schedule:project:read',
    'schedule:cluster:write','schedule:cluster:read',
    'schedule:node:write','schedule:node:read','schedule:node:test',
    'schedule:queue:write','schedule:queue:read',
    'schedule:credential:write','schedule:credential:read','schedule:credential:rotate',
    'schedule:operator:read','schedule:operator:write','schedule:operator:dryrun',
    'schedule:workflow:read','schedule:workflow:write','schedule:workflow:execute',
    'schedule:trigger:read',
    'schedule:task:read','schedule:task:log:grant','schedule:task:stop',
    'schedule:task:retry','schedule:task:enqueue_front','schedule:task:submit',
    'schedule:backfill:read','schedule:backfill:write',
    'schedule:alert:read','schedule:alert:write',
    'schedule:audit:self',
    'schedule:platform:health','schedule:platform:migrate','schedule:platform:view:ops',
    'schedule:openapi:read');

-- 项目管理员 PROJECT_ADMIN
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.role_code = 'PROJECT_ADMIN'
  AND p.perm_code IN (
    'schedule:project:write','schedule:project:read','schedule:project:member','schedule:project:quota',
    'schedule:cluster:read','schedule:node:read','schedule:queue:read',
    'schedule:credential:write','schedule:credential:read','schedule:credential:rotate',
    'schedule:operator:read','schedule:operator:write','schedule:operator:delete',
    'schedule:operator:publish','schedule:operator:dryrun','schedule:operator:param',
    'schedule:workflow:read','schedule:workflow:write','schedule:workflow:publish',
    'schedule:workflow:execute',
    'schedule:trigger:write','schedule:trigger:read',
    'schedule:task:read','schedule:task:log:raw','schedule:task:stop','schedule:task:retry',
    'schedule:task:submit',
    'schedule:backfill:read','schedule:backfill:write',
    'schedule:alert:read','schedule:alert:write',
    'schedule:audit:project',
    'schedule:openapi:read','schedule:openapi:write');

-- 算子维护者 OPERATOR_MAINTAINER
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.role_code = 'OPERATOR_MAINTAINER'
  AND p.perm_code IN (
    'schedule:project:read',
    'schedule:operator:read','schedule:operator:write','schedule:operator:publish',
    'schedule:operator:dryrun','schedule:operator:param',
    'schedule:workflow:read','schedule:workflow:write','schedule:workflow:execute',
    'schedule:trigger:read',
    'schedule:task:read','schedule:task:log:raw','schedule:task:submit',
    'schedule:audit:self');

-- 业务人员 BUSINESS
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.role_code = 'BUSINESS'
  AND p.perm_code IN (
    'schedule:project:read',
    'schedule:workflow:read','schedule:workflow:write','schedule:workflow:execute',
    'schedule:trigger:read',
    'schedule:task:read','schedule:task:log:raw','schedule:task:stop','schedule:task:retry',
    'schedule:task:submit',
    'schedule:backfill:read',
    'schedule:audit:self');

-- 只读/审计 AUDITOR（凭据不授：凭据列表对审计只开放台账，避免敏感面）
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.role_code = 'AUDITOR'
  AND p.perm_code IN (
    'schedule:tenant:read',
    'schedule:project:read',
    'schedule:cluster:read','schedule:node:read','schedule:queue:read',
    'schedule:operator:read','schedule:workflow:read','schedule:trigger:read',
    'schedule:task:read','schedule:task:log:raw',
    'schedule:backfill:read','schedule:alert:read','schedule:audit:read');

-- ── 平台管理员账号（M0 联调用；生产环境必须改密或禁用）────────
-- 密码：Admin@123（BCrypt $2a$10$，安装后立即修改）
INSERT INTO app_user (user_id, username, display_name, password_hash, tenant_id, status, created_by)
VALUES ('USER-0001', 'admin', '平台管理员',
        '$2a$10$zwa3ZkHsoibWNKCWYNDtKe1kBldlfmc7BiPRsh/Ob/1H8X8PpaVjK',
        (SELECT id FROM tenant WHERE tenant_id = 'TENANT-0001'), 'ENABLED', 'system');

INSERT INTO user_role (user_id, role_id, granted_by)
SELECT u.id, r.id, 'system'
  FROM app_user u, role r
 WHERE u.username = 'admin' AND r.role_code = 'PLATFORM_ADMIN';

-- ── 平台配置默认值（docs/05 §3.7）────────────────────────────
INSERT INTO platform_config (config_key, config_value, config_group, value_type, description) VALUES
('log.hot_retention_days',      '30', 'LOG', 'NUMBER', '日志热存周期（Q-04，可配置）'),
('log.cold_retention_days',     '90', 'LOG', 'NUMBER', '日志冷存周期（PRD §13.2）'),
('task.max_retry',              '10', 'TASK', 'NUMBER', '重试硬上限（E-04）'),
('alert.default_suppress_minutes', '30', 'ALERT', 'NUMBER', '告警抑制窗口默认值（PRD §10.12）'),
('queue.default_priority_max',  '100', 'QUEUE', 'NUMBER', '优先级上限（E-07：0~100，默认 0）');
