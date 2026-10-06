-- =====================================================================
-- FlowOps V5 · 种子数据：MVP 5 类告警事件默认规则（默认开启，PRD §10.12 设计原则）
-- 事件级别：任务失败/超时/重试耗尽=严重；节点离线=警告；并发达限=提示
-- =====================================================================

INSERT INTO alert_rule (rule_id, rule_name, event_type, condition, channels, suppress_window_minutes, enabled, creator)
VALUES
('AR-0001', '任务失败通知',           'TASK_FAILED',          '{}'::jsonb, '{EMAIL,WEBHOOK}', 30, true, 'system'),
('AR-0002', '任务超时通知',           'TASK_TIMEOUT',         '{}'::jsonb, '{EMAIL,WEBHOOK}', 30, true, 'system'),
('AR-0003', '步骤重试耗尽通知',       'STEP_RETRY_EXHAUSTED', '{}'::jsonb, '{EMAIL,WEBHOOK}', 30, true, 'system'),
('AR-0004', '执行节点离线告警',       'NODE_OFFLINE',         '{}'::jsonb, '{EMAIL}',         30, true, 'system'),
('AR-0005', '工作流并发达限提示',     'CONCURRENCY_LIMIT',    '{}'::jsonb, '{EMAIL}',         30, true, 'system');
