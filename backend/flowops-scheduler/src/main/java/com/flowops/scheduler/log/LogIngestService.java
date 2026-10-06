package com.flowops.scheduler.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.domain.dto.query.TaskLogRow;
import com.flowops.domain.mapper.task.TaskLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 日志接入服务（docs/03 §4.5 双通道的采集侧；D-10：日志不进主链路）。
 *
 * <p><b>链路</b>：SSH 行回调 → {@link #append}（无锁入队）→ flusher 每 500ms 批量取走 →
 * ① Redis INCRBY 发号（M-09 同源：严格单调，offset 续传的依据）→
 * ② task_log 批量落库（30 天热存，PRD §13.2）→ ③ Redis Pub/Sub 发布（server 的
 * WebSocket 订阅转发，端到端延迟 ≤ 5s 达标）。</p>
 *
 * <p><b>背压策略</b>：单缓冲容量上限（超出丢最旧并打标 WARN）——日志是观测数据，
 * 挤爆内存去保日志是本末倒置；正常算子输出量远达不到上限。</p>
 *
 * <p><b>脱敏边界</b>：敏感行脱敏在 server 推送侧执行（PRD §13.3 硬要求：脱敏不可绕过，
 * 前端不接触原文）——本类只存原文，历史任务回放走同一出口。</p>
 */
@Slf4j
public class LogIngestService {

    public static final String SEQ_PREFIX = "flowops:log:seq:";
    public static final String CHANNEL_PREFIX = "flowops:log:stream:";
    private static final int MAX_BUFFER_LINES = 10_000;

    private final TaskLogMapper taskLogMapper;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** stepRowId → 行缓冲（flusher 消费）。 */
    private final Map<Long, StepBuffer> buffers = new ConcurrentHashMap<>();

    public LogIngestService(TaskLogMapper taskLogMapper, RedissonClient redisson) {
        this.taskLogMapper = taskLogMapper;
        this.redisson = redisson;
    }

    /** SSH 行回调入口（执行线程调用，无锁、不阻塞）。 */
    public void append(long taskRowId, long taskStepRowId, String stepInstanceId, String stream, String line) {
        StepBuffer buffer = buffers.computeIfAbsent(taskStepRowId,
                k -> new StepBuffer(taskRowId, taskStepRowId, stepInstanceId));
        buffer.offer(stream, line);
    }

    /**
     * 批量刷盘（SchedulerEngine 每 500ms 调一次；异常不外抛——日志缺失不是 P0，但要留痕）。
     *
     * @return 本轮落库行数（指标 log.ingest.batch 的数据源）
     */
    public int flush() {
        int total = 0;
        for (StepBuffer buffer : buffers.values()) {
            try {
                total += flushOne(buffer);
            } catch (Exception e) {
                log.error("日志刷盘失败 step={}（丢 {} 行）", buffer.stepInstanceId, buffer.pendingSize(), e);
                buffer.clear();
            }
        }
        // 空缓冲定期回收，防 Map 无界增长
        buffers.values().removeIf(b -> b.pendingSize() == 0 && b.idle());
        return total;
    }

    private int flushOne(StepBuffer buffer) {
        List<String[]> lines = buffer.drain();
        if (lines.isEmpty()) {
            return 0;
        }
        // ① 发号：一次 INCRBY 预留区间，本地分配（避免每行一次 Redis 往返）
        Long base = redisson.getAtomicLong(SEQ_PREFIX + buffer.taskStepRowId)
                .addAndGet(lines.size());
        long firstSeq = base - lines.size() + 1;

        List<TaskLogRow> rows = new ArrayList<>(lines.size());
        List<Map<String, Object>> frames = new ArrayList<>(lines.size());
        long seq = firstSeq;
        for (String[] line : lines) {   // [stream, content]
            TaskLogRow row = new TaskLogRow();
            row.setTaskStepId(buffer.taskStepRowId);
            row.setTaskId(buffer.taskRowId);
            row.setSeq(seq);
            row.setStream(line[0]);
            row.setContent(line[1]);
            rows.add(row);
            frames.add(Map.of("type", "LOG", "seq", seq, "stream", line[0], "content", line[1]));
            seq++;
        }

        // ② 落库 + ③ 发布（发布失败不影响落库：断线重连按 offset 从 DB 补发，PRD §13.2）
        taskLogMapper.batchInsert(rows);
        RTopic topic = redisson.getTopic(CHANNEL_PREFIX + buffer.taskStepRowId);
        for (Map<String, Object> frame : frames) {
            try {
                topic.publish(objectMapper.writeValueAsString(frame));
            } catch (Exception e) {
                log.warn("日志帧发布失败（DB 已落，重连可补）step={} seq={}", buffer.stepInstanceId, frame.get("seq"));
            }
        }
        return rows.size();
    }

    /** 单步骤行缓冲。 */
    private static final class StepBuffer {

        private final long taskRowId;
        private final long taskStepRowId;
        private final String stepInstanceId;
        /** 元素为 [stream, content] */
        private final ConcurrentLinkedDeque<String[]> lines = new ConcurrentLinkedDeque<>();
        private final AtomicInteger size = new AtomicInteger();
        private volatile long lastActivityAt = System.currentTimeMillis();

        StepBuffer(long taskRowId, long taskStepRowId, String stepInstanceId) {
            this.taskRowId = taskRowId;
            this.taskStepRowId = taskStepRowId;
            this.stepInstanceId = stepInstanceId;
        }

        void offer(String stream, String content) {
            lastActivityAt = System.currentTimeMillis();
            if (size.get() >= MAX_BUFFER_LINES) {
                // 背压：丢最旧（观测数据优先保内存；docs/03 §4.5 的"大日志模式"是 M4 的产品化方案）
                lines.pollFirst();
                size.decrementAndGet();
                log.warn("日志缓冲超限丢最旧行 step={}", stepInstanceId);
            }
            lines.offerLast(new String[]{stream, content});
            size.incrementAndGet();
        }

        List<String[]> drain() {
            List<String[]> drained = new ArrayList<>(size.get());
            String[] line;
            while ((line = lines.pollFirst()) != null) {
                drained.add(line);
            }
            size.set(0);
            return drained;
        }

        int pendingSize() {
            return size.get();
        }

        boolean idle() {
            return System.currentTimeMillis() - lastActivityAt > 60_000;
        }

        void clear() {
            lines.clear();
            size.set(0);
        }
    }
}
