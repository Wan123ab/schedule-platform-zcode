package com.flowops.modules.asset.service;

import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.entity.asset.Queue;
import com.flowops.domain.mapper.asset.QueueMapper;
import com.flowops.modules.asset.converter.QueueConverterImpl;
import com.flowops.modules.asset.dto.QueueVO;
import com.flowops.modules.asset.dto.SaveQueueRequest;
import com.flowops.modules.governance.scope.ScopeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 队列域单测（docs/05 §3.3 / docs/06 §6.1）：
 * 两个并发阈值的默认值、同集群重名、禁用/删除的作业面闸门、跨集群迁移禁令。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueueServiceTest {

    @Mock private QueueMapper queueMapper;
    @Mock private ClusterService clusterService;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private QueueService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.increment(anyString())).thenReturn(2L);
        service = new QueueService(queueMapper, clusterService, new IdGen(redis), new QueueConverterImpl(),
                new ScopeGuard());
    }

    private Cluster cluster() {
        Cluster c = new Cluster();
        c.setId(1L);
        c.setClusterId("CL-20261007-0001");
        c.setClusterName("生产集群A");
        return c;
    }

    private Queue queue() {
        Queue q = new Queue();
        q.setId(4L);
        q.setQueueId("QU-20261007-0001");
        q.setQueueName("默认队列");
        q.setClusterId(1L);
        q.setStatus("ENABLED");
        q.setMaxConcurrentTasks(3);
        q.setMaxWaitingTasks(50);
        q.setDefaultPriority(0);
        return q;
    }

    @Test
    void 创建_编号前缀QU_阈值取默认值_回显集群编号与名称() {
        when(clusterService.requireVisible("CL-20261007-0001")).thenReturn(cluster());
        when(queueMapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        when(queueMapper.insert(captor.capture())).thenReturn(1);

        SaveQueueRequest request = new SaveQueueRequest();
        request.setQueueName("默认队列");
        QueueVO vo = service.create("CL-20261007-0001", request);

        Queue saved = captor.getValue();
        assertThat(saved.getQueueId()).startsWith("QU-");
        assertThat(saved.getStatus()).isEqualTo("ENABLED");
        assertThat(saved.getMaxConcurrentTasks()).isEqualTo(3);    // 出队时判定
        assertThat(saved.getMaxWaitingTasks()).isEqualTo(50);      // 提交时判定
        assertThat(saved.getWaitTimeoutSeconds()).isEqualTo(3600);
        assertThat(vo.getClusterId()).isEqualTo("CL-20261007-0001");
        assertThat(vo.getClusterName()).isEqualTo("生产集群A");
    }

    @Test
    void 创建_同集群下重名_拒绝() {
        when(clusterService.requireVisible("CL-20261007-0001")).thenReturn(cluster());
        when(queueMapper.selectCount(any())).thenReturn(1L);

        SaveQueueRequest request = new SaveQueueRequest();
        request.setQueueName("默认队列");

        assertThatThrownBy(() -> service.create("CL-20261007-0001", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("队列名称已存在");
        verify(queueMapper, never()).insert(any(Queue.class));
    }

    @Test
    void 更新_试图跨集群迁移_拒绝() {
        when(queueMapper.selectOne(any())).thenReturn(queue());
        Cluster other = cluster();
        other.setId(2L);
        other.setClusterId("CL-20261007-0002");
        when(clusterService.requireVisible("CL-20261007-0002")).thenReturn(other);

        SaveQueueRequest request = new SaveQueueRequest();
        request.setQueueName("默认队列");
        request.setClusterId("CL-20261007-0002");

        assertThatThrownBy(() -> service.update("QU-20261007-0001", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("跨集群迁移");
        verify(queueMapper, never()).updateById(any(Queue.class));
    }

    @Test
    void 禁用队列_有运行中任务_拒绝() {
        when(queueMapper.selectOne(any())).thenReturn(queue());
        when(queueMapper.countRunningTasks(4L)).thenReturn(2L);

        assertThatThrownBy(() -> service.updateStatus("QU-20261007-0001", "DISABLED"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("运行中任务");
        verify(queueMapper, never()).updateById(any(Queue.class));
    }

    @Test
    void 禁用队列_空闲_成功() {
        when(queueMapper.selectOne(any())).thenReturn(queue());
        when(queueMapper.countRunningTasks(4L)).thenReturn(0L);
        when(clusterService.findById(1L)).thenReturn(cluster());

        QueueVO vo = service.updateStatus("QU-20261007-0001", "DISABLED");

        assertThat(vo.getStatus()).isEqualTo("DISABLED");
        verify(queueMapper).updateById(any(Queue.class));
    }

    @Test
    void 禁用队列_已经是禁用态_幂等() {
        Queue disabled = queue();
        disabled.setStatus("DISABLED");
        when(queueMapper.selectOne(any())).thenReturn(disabled);
        when(clusterService.findById(1L)).thenReturn(cluster());

        service.updateStatus("QU-20261007-0001", "DISABLED");

        verify(queueMapper, never()).updateById(any(Queue.class));
    }

    @Test
    void 删除队列_仍有未终结任务_拒绝并给出两段计数() {
        when(queueMapper.selectOne(any())).thenReturn(queue());
        when(queueMapper.countWaitingTasks(4L)).thenReturn(5L);
        when(queueMapper.countRunningTasks(4L)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete("QU-20261007-0001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未终结任务");
        verify(queueMapper, never()).softDelete(any());
    }

    @Test
    void 删除队列_干净_走显式XML软删除() {
        when(queueMapper.selectOne(any())).thenReturn(queue());
        when(queueMapper.countWaitingTasks(4L)).thenReturn(0L);
        when(queueMapper.countRunningTasks(4L)).thenReturn(0L);

        service.delete("QU-20261007-0001");

        verify(queueMapper).softDelete(4L);
    }
}
