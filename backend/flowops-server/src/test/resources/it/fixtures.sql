-- =====================================================================
-- 集成测试夹具（O-9 越权矩阵 / O-18 controller 契约）
--
-- 三条纪律：
--  1. **自清理**：所有行以 IT- 业务码打标，脚本开头按「反向依赖顺序」先删后插，
--     故可重复执行；不依赖"干净库"，也不去动 V4 种子（角色/权限/管理员）。
--  2. **只造最小合法行**：夹具不是业务数据，只求满足 NOT NULL / CHECK / FK。
--     业务语义（发号、校验、审计）由被测端点自己承担 —— 用 SQL 绕过它们正是
--     为了让"越权判定"成为唯一变量，不被创建期校验抢先拒绝。
--  3. **两个项目严格镜像**：A 与 B 的资源一一对应，越权断言才能写成
--     "同一端点换 B 的编号 → 40301"，否则无法区分"越权"与"数据本身有问题"。
-- =====================================================================

-- ── ① 清理（反依赖顺序）──────────────────────────────────────────
UPDATE workflow SET current_version_id = NULL WHERE workflow_id LIKE 'WF-IT-%';
UPDATE project  SET owner_user_id      = NULL WHERE project_id  LIKE 'PRJ-IT-%';
UPDATE "trigger" SET last_fire_task_id = NULL WHERE trigger_id  LIKE 'TRG-IT-%';

DELETE FROM task             WHERE task_id           LIKE 'TASK-IT-%';
DELETE FROM backfill_batch   WHERE batch_id          LIKE 'BF-IT-%';
DELETE FROM "trigger"        WHERE trigger_id        LIKE 'TRG-IT-%';
DELETE FROM workflow_version WHERE version_id        LIKE 'WFV-IT-%';
DELETE FROM workflow         WHERE workflow_id       LIKE 'WF-IT-%';
DELETE FROM operator_version WHERE version_id        LIKE 'OPV-IT-%';
DELETE FROM operator         WHERE operator_id       LIKE 'OP-IT-%';
DELETE FROM executor_node    WHERE executor_node_id  LIKE 'EN-IT-%';
DELETE FROM queue            WHERE queue_id          LIKE 'QU-IT-%';
DELETE FROM credential       WHERE credential_id     LIKE 'CR-IT-%';
DELETE FROM project_cluster  WHERE project_id IN (SELECT id FROM project WHERE project_id LIKE 'PRJ-IT-%');
DELETE FROM cluster          WHERE cluster_id        LIKE 'CL-IT-%';
DELETE FROM project_member   WHERE project_id IN (SELECT id FROM project WHERE project_id LIKE 'PRJ-IT-%');
DELETE FROM project          WHERE project_id        LIKE 'PRJ-IT-%';
DELETE FROM user_role        WHERE user_id IN (SELECT id FROM app_user WHERE username LIKE 'it-%');
DELETE FROM app_user         WHERE username          LIKE 'it-%';

-- ── ② 两个项目 ──────────────────────────────────────────────────
INSERT INTO project (project_id, project_name, tenant_id, description, status, created_by, updated_by)
SELECT 'PRJ-IT-A', 'IT 项目 A', t.id, '越权矩阵夹具：用户 it-user-a 的项目', 'ENABLED', 'it-fixture', 'it-fixture'
  FROM tenant t WHERE t.tenant_id = 'TENANT-0001';

INSERT INTO project (project_id, project_name, tenant_id, description, status, created_by, updated_by)
SELECT 'PRJ-IT-B', 'IT 项目 B', t.id, '越权矩阵夹具：用户 it-user-a 不可见的项目', 'ENABLED', 'it-fixture', 'it-fixture'
  FROM tenant t WHERE t.tenant_id = 'TENANT-0001';

-- ── ③ 三个用户（密码与种子管理员一致：Admin@123）─────────────────
--     it-user-a：PROJECT_ADMIN + A 项目成员  → PROJECT 范围，可见 {A}
--     it-user-b：PROJECT_ADMIN + B 项目成员  → PROJECT 范围，可见 {B}（正例对照）
--     it-ops   ：OPS           + A 项目成员  → AUTHORIZED_CLUSTER，可见 {CL-IT-1}
INSERT INTO app_user (user_id, username, display_name, password_hash, tenant_id, status, created_by, updated_by)
SELECT 'USER-IT-A', 'it-user-a', 'IT 用户 A',
       (SELECT password_hash FROM app_user WHERE username = 'admin'),
       t.id, 'ENABLED', 'it-fixture', 'it-fixture'
  FROM tenant t WHERE t.tenant_id = 'TENANT-0001';

INSERT INTO app_user (user_id, username, display_name, password_hash, tenant_id, status, created_by, updated_by)
SELECT 'USER-IT-B', 'it-user-b', 'IT 用户 B',
       (SELECT password_hash FROM app_user WHERE username = 'admin'),
       t.id, 'ENABLED', 'it-fixture', 'it-fixture'
  FROM tenant t WHERE t.tenant_id = 'TENANT-0001';

INSERT INTO app_user (user_id, username, display_name, password_hash, tenant_id, status, created_by, updated_by)
SELECT 'USER-IT-OPS', 'it-ops', 'IT 运维',
       (SELECT password_hash FROM app_user WHERE username = 'admin'),
       t.id, 'ENABLED', 'it-fixture', 'it-fixture'
  FROM tenant t WHERE t.tenant_id = 'TENANT-0001';

INSERT INTO user_role (user_id, role_id, granted_by)
SELECT u.id, r.id, 'it-fixture' FROM app_user u, role r
 WHERE u.username = 'it-user-a' AND r.role_code = 'PROJECT_ADMIN';
INSERT INTO user_role (user_id, role_id, granted_by)
SELECT u.id, r.id, 'it-fixture' FROM app_user u, role r
 WHERE u.username = 'it-user-b' AND r.role_code = 'PROJECT_ADMIN';
INSERT INTO user_role (user_id, role_id, granted_by)
SELECT u.id, r.id, 'it-fixture' FROM app_user u, role r
 WHERE u.username = 'it-ops'    AND r.role_code = 'OPS';

INSERT INTO project_member (project_id, user_id, project_role, added_by)
SELECT p.id, u.id, 'PROJECT_ADMIN', 'it-fixture' FROM project p, app_user u
 WHERE p.project_id = 'PRJ-IT-A' AND u.username = 'it-user-a';
INSERT INTO project_member (project_id, user_id, project_role, added_by)
SELECT p.id, u.id, 'PROJECT_ADMIN', 'it-fixture' FROM project p, app_user u
 WHERE p.project_id = 'PRJ-IT-B' AND u.username = 'it-user-b';
-- it-ops 挂在 A 项目上：AUTHORIZED_CLUSTER 的可见集群来自 project_cluster（ClusterMapper 口径）
INSERT INTO project_member (project_id, user_id, project_role, added_by)
SELECT p.id, u.id, 'OPS', 'it-fixture' FROM project p, app_user u
 WHERE p.project_id = 'PRJ-IT-A' AND u.username = 'it-ops';

-- ── ④ 集群 / 队列 / 节点（A 与 B 各一套，A 的集群授权给 A 项目）──
INSERT INTO cluster (cluster_id, cluster_name, created_by, updated_by)
VALUES ('CL-IT-1', 'IT 集群 1', 'it-fixture', 'it-fixture'),
       ('CL-IT-2', 'IT 集群 2', 'it-fixture', 'it-fixture');

INSERT INTO project_cluster (project_id, cluster_id)
SELECT p.id, c.id FROM project p, cluster c
 WHERE p.project_id = 'PRJ-IT-A' AND c.cluster_id = 'CL-IT-1';
INSERT INTO project_cluster (project_id, cluster_id)
SELECT p.id, c.id FROM project p, cluster c
 WHERE p.project_id = 'PRJ-IT-B' AND c.cluster_id = 'CL-IT-2';

INSERT INTO queue (queue_id, queue_name, cluster_id, created_by, updated_by)
SELECT 'QU-IT-1', 'IT 队列 1', c.id, 'it-fixture', 'it-fixture' FROM cluster c WHERE c.cluster_id = 'CL-IT-1';
INSERT INTO queue (queue_id, queue_name, cluster_id, created_by, updated_by)
SELECT 'QU-IT-2', 'IT 队列 2', c.id, 'it-fixture', 'it-fixture' FROM cluster c WHERE c.cluster_id = 'CL-IT-2';

INSERT INTO executor_node (executor_node_id, executor_node_name, cluster_id, ip, os_type, online_status, created_by, updated_by)
SELECT 'EN-IT-1', 'IT 节点 1', c.id, '10.0.0.1', 'LINUX', 'ONLINE', 'it-fixture', 'it-fixture' FROM cluster c WHERE c.cluster_id = 'CL-IT-1';
INSERT INTO executor_node (executor_node_id, executor_node_name, cluster_id, ip, os_type, online_status, created_by, updated_by)
SELECT 'EN-IT-2', 'IT 节点 2', c.id, '10.0.0.2', 'LINUX', 'ONLINE', 'it-fixture', 'it-fixture' FROM cluster c WHERE c.cluster_id = 'CL-IT-2';

-- ── ⑤ 凭据（project_id 可空；此处分别挂 A/B 便于观察项目维度行为）──
INSERT INTO credential (credential_id, credential_name, credential_type, username,
                        secret_encrypted, secret_fingerprint, project_id, status, created_by, updated_by)
SELECT 'CR-IT-1', 'IT 凭据 1', 'SSH_KEY', 'it', 'it-cipher-1', 'fp-it-0001', p.id, 'VALID', 'it-fixture', 'it-fixture'
  FROM project p WHERE p.project_id = 'PRJ-IT-A';
INSERT INTO credential (credential_id, credential_name, credential_type, username,
                        secret_encrypted, secret_fingerprint, project_id, status, created_by, updated_by)
SELECT 'CR-IT-2', 'IT 凭据 2', 'SSH_KEY', 'it', 'it-cipher-2', 'fp-it-0002', p.id, 'VALID', 'it-fixture', 'it-fixture'
  FROM project p WHERE p.project_id = 'PRJ-IT-B';

-- ── ⑥ 算子 + 版本 ───────────────────────────────────────────────
INSERT INTO operator (operator_id, operator_name, operator_type, project_id, description, creator, created_by, updated_by)
SELECT 'OP-IT-A', 'IT 算子 A', 'SHELL', p.id, '夹具', 'it-fixture', 'it-fixture', 'it-fixture'
  FROM project p WHERE p.project_id = 'PRJ-IT-A';
INSERT INTO operator (operator_id, operator_name, operator_type, project_id, description, creator, created_by, updated_by)
SELECT 'OP-IT-B', 'IT 算子 B', 'SHELL', p.id, '夹具', 'it-fixture', 'it-fixture', 'it-fixture'
  FROM project p WHERE p.project_id = 'PRJ-IT-B';

INSERT INTO operator_version (version_id, operator_id, version_no, os_type, start_command, created_by, updated_by)
SELECT 'OPV-IT-A1', o.id, 'v1', 'LINUX', 'echo a', 'it-fixture', 'it-fixture' FROM operator o WHERE o.operator_id = 'OP-IT-A';
INSERT INTO operator_version (version_id, operator_id, version_no, os_type, start_command, created_by, updated_by)
SELECT 'OPV-IT-B1', o.id, 'v1', 'LINUX', 'echo b', 'it-fixture', 'it-fixture' FROM operator o WHERE o.operator_id = 'OP-IT-B';

-- ── ⑦ 工作流 + 版本 + 触发器 + 回填批次 + 任务 ───────────────────
INSERT INTO workflow (workflow_id, workflow_name, project_id, creator, created_by, updated_by)
SELECT 'WF-IT-A', 'IT 工作流 A', p.id, 'it-fixture', 'it-fixture', 'it-fixture' FROM project p WHERE p.project_id = 'PRJ-IT-A';
INSERT INTO workflow (workflow_id, workflow_name, project_id, creator, created_by, updated_by)
SELECT 'WF-IT-B', 'IT 工作流 B', p.id, 'it-fixture', 'it-fixture', 'it-fixture' FROM project p WHERE p.project_id = 'PRJ-IT-B';

INSERT INTO workflow_version (version_id, workflow_id, version_no, created_by, updated_by)
SELECT 'WFV-IT-A1', w.id, 'v1', 'it-fixture', 'it-fixture' FROM workflow w WHERE w.workflow_id = 'WF-IT-A';
INSERT INTO workflow_version (version_id, workflow_id, version_no, created_by, updated_by)
SELECT 'WFV-IT-B1', w.id, 'v1', 'it-fixture', 'it-fixture' FROM workflow w WHERE w.workflow_id = 'WF-IT-B';

UPDATE workflow w SET current_version_id = v.id
  FROM workflow_version v
 WHERE v.version_id = 'WFV-IT-A1' AND w.workflow_id = 'WF-IT-A';
UPDATE workflow w SET current_version_id = v.id
  FROM workflow_version v
 WHERE v.version_id = 'WFV-IT-B1' AND w.workflow_id = 'WF-IT-B';

INSERT INTO "trigger" (trigger_id, trigger_name, workflow_id, trigger_type, cron_expression, created_by, updated_by)
SELECT 'TRG-IT-A1', 'IT 触发器 A', w.id, 'CRON', '0 0 2 * * *', 'it-fixture', 'it-fixture' FROM workflow w WHERE w.workflow_id = 'WF-IT-A';
INSERT INTO "trigger" (trigger_id, trigger_name, workflow_id, trigger_type, cron_expression, created_by, updated_by)
SELECT 'TRG-IT-B1', 'IT 触发器 B', w.id, 'CRON', '0 0 3 * * *', 'it-fixture', 'it-fixture' FROM workflow w WHERE w.workflow_id = 'WF-IT-B';

INSERT INTO backfill_batch (batch_id, batch_name, workflow_id, workflow_version_id, project_id,
                            biz_from, biz_to, submitter, created_by, updated_by)
SELECT 'BF-IT-A1', 'IT 批次 A', w.id, v.id, w.project_id, current_date - 2, current_date - 1,
       'it-user-a', 'it-fixture', 'it-fixture'
  FROM workflow w, workflow_version v
 WHERE w.workflow_id = 'WF-IT-A' AND v.version_id = 'WFV-IT-A1';
INSERT INTO backfill_batch (batch_id, batch_name, workflow_id, workflow_version_id, project_id,
                            biz_from, biz_to, submitter, created_by, updated_by)
SELECT 'BF-IT-B1', 'IT 批次 B', w.id, v.id, w.project_id, current_date - 2, current_date - 1,
       'it-user-b', 'it-fixture', 'it-fixture'
  FROM workflow w, workflow_version v
 WHERE w.workflow_id = 'WF-IT-B' AND v.version_id = 'WFV-IT-B1';

INSERT INTO task (task_id, workflow_id, workflow_name, workflow_version_id, workflow_version,
                  project_id, trigger_type, status, submitter, biz_date, created_by, updated_by)
SELECT 'TASK-IT-A1', w.id, w.workflow_name, v.id, 'v1', w.project_id, 'MANUAL', 'PENDING',
       'it-user-a', current_date - 1, 'it-fixture', 'it-fixture'
  FROM workflow w, workflow_version v
 WHERE w.workflow_id = 'WF-IT-A' AND v.version_id = 'WFV-IT-A1';
INSERT INTO task (task_id, workflow_id, workflow_name, workflow_version_id, workflow_version,
                  project_id, trigger_type, status, submitter, biz_date, created_by, updated_by)
SELECT 'TASK-IT-B1', w.id, w.workflow_name, v.id, 'v1', w.project_id, 'MANUAL', 'PENDING',
       'it-user-b', current_date - 1, 'it-fixture', 'it-fixture'
  FROM workflow w, workflow_version v
 WHERE w.workflow_id = 'WF-IT-B' AND v.version_id = 'WFV-IT-B1';
