-- =====================================================================
-- FlowOps V2 · task_log 分区表（docs/05 §7.2）
-- 按日 RANGE 分区；DROP PARTITION 秒级清理（30 天热 / 90 天冷，PRD §13.2）
-- =====================================================================

CREATE TABLE task_log (
    id bigserial,
    task_step_id bigint NOT NULL,
    task_id      bigint NOT NULL,
    seq          bigint NOT NULL,                      -- 全局递增序号（offset 续传依据）
    stream       varchar(8) NOT NULL DEFAULT 'EOF'      -- EOF=stdout / ERR=stderr（PRD §13.1-5）
                 CHECK (stream IN ('EOF','ERR')),
    log_time     timestamptz NOT NULL DEFAULT now(),
    content      text NOT NULL,
    storage_tier varchar(16) NOT NULL DEFAULT 'HOT'
                 CHECK (storage_tier IN ('HOT','COLD')),
    PRIMARY KEY (id, log_time)
) PARTITION BY RANGE (log_time);

-- 建分区函数（幂等，可重复调用；应用层定时预建未来 3 天分区）
CREATE OR REPLACE FUNCTION ensure_task_log_partition(p_date date)
RETURNS void AS $$
DECLARE
    part_name text := 'task_log_' || to_char(p_date, 'YYYYMMDD');
    start_ts  text := to_char(p_date, 'YYYY-MM-DD');
    end_ts    text := to_char(p_date + 1, 'YYYY-MM-DD');
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = part_name) THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF task_log FOR VALUES FROM (%L) TO (%L)',
            part_name, start_ts, end_ts);
        EXECUTE format('CREATE INDEX %I ON %I(task_step_id, seq)',
                       'idx_' || part_name || '_step_seq', part_name);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- 迁移期预建今天起 4 个分区，避免启动首日插入失败
SELECT ensure_task_log_partition(d::date)
  FROM generate_series(current_date, current_date + 3, interval '1 day') AS d;
