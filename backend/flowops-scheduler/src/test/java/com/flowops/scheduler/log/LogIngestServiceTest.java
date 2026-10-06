package com.flowops.scheduler.log;

import com.flowops.domain.dto.query.TaskLogRow;
import com.flowops.domain.mapper.task.TaskLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日志接入单测：发号区间分配、批量落库、逐帧发布（docs/03 §4.5 双通道采集侧）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogIngestServiceTest {

    @Mock private TaskLogMapper taskLogMapper;
    @Mock private RedissonClient redisson;
    @Mock private RAtomicLong seqCounter;
    @Mock private RTopic topic;

    private LogIngestService ingest;

    @BeforeEach
    void setUp() {
        when(redisson.getAtomicLong(anyString())).thenReturn(seqCounter);
        when(redisson.getTopic(anyString())).thenReturn(topic);
        ingest = new LogIngestService(taskLogMapper, redisson);
    }

    @Test
    void 三行日志_一次发号_批量落库_逐帧发布() {
        when(seqCounter.addAndGet(3L)).thenReturn(103L);   // 预留 [101,103]

        ingest.append(1L, 50L, "SI-50", "EOF", "第一行");
        ingest.append(1L, 50L, "SI-50", "EOF", "第二行");
        ingest.append(1L, 50L, "SI-50", "ERR", "报错行");
        int flushed = ingest.flush();

        assertThat(flushed).isEqualTo(3);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskLogRow>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(taskLogMapper).batchInsert(captor.capture());
        List<TaskLogRow> rows = captor.getValue();
        assertThat(rows).extracting(TaskLogRow::getSeq)
                .containsExactly(101L, 102L, 103L);            // seq 严格连续（offset 续传依据）
        assertThat(rows).extracting(TaskLogRow::getStream)
                .containsExactly("EOF", "EOF", "ERR");
        verify(topic, times(3)).publish(anyString());
    }

    @Test
    void 空缓冲_不触发任何写() {
        assertThat(ingest.flush()).isZero();
        org.mockito.Mockito.verifyNoInteractions(taskLogMapper);
    }

    @Test
    void 落库异常_不外抛_缓冲清空防重放大() {
        when(seqCounter.addAndGet(1L)).thenReturn(1L);
        when(taskLogMapper.batchInsert(any())).thenThrow(new RuntimeException("db down"));

        ingest.append(1L, 50L, "SI-50", "EOF", "line");
        int flushed = ingest.flush();   // 异常被吞（日志缺失非 P0），但不阻塞调度

        assertThat(flushed).isZero();
        assertThat(ingest.flush()).isZero();   // 缓冲已清空：不会在下一轮重放旧行
    }
}
