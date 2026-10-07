package com.flowops.modules.asset.service;

import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.asset.Cluster;
import com.flowops.domain.mapper.asset.ClusterMapper;
import com.flowops.modules.asset.converter.ClusterConverterImpl;
import com.flowops.modules.asset.dto.ClusterVO;
import com.flowops.modules.asset.dto.SaveClusterRequest;
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
 * 集群域单测（docs/05 §3.3 / docs/07 §4.2）：
 * 42204 删除闸门（实时 COUNT 而非 node_total 快照）、40400/40301 区分、命名唯一、编号生成。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClusterServiceTest {

    @Mock private ClusterMapper clusterMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private ClusterService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.increment(anyString())).thenReturn(7L);
        service = new ClusterService(clusterMapper, new IdGen(redis), new ClusterConverterImpl(), new ScopeGuard());
    }

    private Cluster cluster() {
        Cluster c = new Cluster();
        c.setId(1L);
        c.setClusterId("CL-20261007-0001");
        c.setClusterName("生产集群A");
        c.setClusterType("GENERAL");
        c.setStatus("NORMAL");
        c.setNodeTotal(3);          // 快照说 3 —— 但删除闸门必须用实时 COUNT
        return c;
    }

    // ── 创建 ────────────────────────────────────────────────

    @Test
    void 创建_编号前缀CL_默认NORMAL与GENERAL_统计从零起() {
        when(clusterMapper.selectCount(any())).thenReturn(0L);
        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        when(clusterMapper.insert(captor.capture())).thenReturn(1);

        SaveClusterRequest request = new SaveClusterRequest();
        request.setClusterName("生产集群A");
        ClusterVO vo = service.create(request);

        Cluster saved = captor.getValue();
        assertThat(vo.getClusterId()).startsWith("CL-");
        assertThat(saved.getStatus()).isEqualTo("NORMAL");
        assertThat(saved.getClusterType()).isEqualTo("GENERAL");
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getDeleted()).isFalse();
        assertThat(saved.getNodeTotal()).isZero();
        assertThat(saved.getCpuTotal()).isEqualByComparingTo("0");
    }

    @Test
    void 创建_名称重复_拒绝并给出人话错误而非DB唯一约束异常() {
        when(clusterMapper.selectCount(any())).thenReturn(1L);

        SaveClusterRequest request = new SaveClusterRequest();
        request.setClusterName("生产集群A");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("集群名称已存在");
        verify(clusterMapper, never()).insert(any(Cluster.class));
    }

    // ── 删除闸门（42204）─────────────────────────────────────

    @Test
    void 删除_集群下仍有节点_42204_且不落删除() {
        when(clusterMapper.selectOne(any())).thenReturn(cluster());
        when(clusterMapper.countNodes(1L)).thenReturn(2L);

        assertThatThrownBy(() -> service.delete("CL-20261007-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42204);

        // 关键：判定依据是实时 COUNT，而不是 node_total 快照（此处快照=3，实时=2，都以实时为准）
        verify(clusterMapper).countNodes(1L);
        verify(clusterMapper, never()).softDelete(any());
    }

    @Test
    void 删除_无节点但仍有队列_同样拒绝_RESTRICT外键不留给DB() {
        when(clusterMapper.selectOne(any())).thenReturn(cluster());
        when(clusterMapper.countNodes(1L)).thenReturn(0L);
        when(clusterMapper.countQueues(1L)).thenReturn(3L);

        assertThatThrownBy(() -> service.delete("CL-20261007-0001"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("调度队列");
        verify(clusterMapper, never()).softDelete(any());
    }

    @Test
    void 删除_干净集群_走显式XML软删除() {
        when(clusterMapper.selectOne(any())).thenReturn(cluster());
        when(clusterMapper.countNodes(1L)).thenReturn(0L);
        when(clusterMapper.countQueues(1L)).thenReturn(0L);

        service.delete("CL-20261007-0001");

        // 不能用 updateById：MP 会把逻辑删除列从 SET 剔除（M2 实测）
        verify(clusterMapper).softDelete(1L);
    }

    // ── 40400 / 40301 ───────────────────────────────────────

    @Test
    void 存在但不可见_40301_而不是谎报不存在() {
        // 第一次（带行级过滤）查不到，第二次（无过滤探测）查得到 → 说明"存在但越权"
        when(clusterMapper.selectOne(any())).thenReturn(null, cluster());

        assertThatThrownBy(() -> service.get("CL-20261007-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40301);
    }

    @Test
    void 真不存在_40400() {
        when(clusterMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.get("CL-9999-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40400);
    }

    // ── 更新 ────────────────────────────────────────────────

    @Test
    void 更新_维护模式开关_落库并回显() {
        when(clusterMapper.selectOne(any())).thenReturn(cluster());
        when(clusterMapper.selectCount(any())).thenReturn(0L);

        SaveClusterRequest request = new SaveClusterRequest();
        request.setClusterName("生产集群A");
        request.setStatus("MAINTENANCE");

        ClusterVO vo = service.update("CL-20261007-0001", request);

        assertThat(vo.getStatus()).isEqualTo("MAINTENANCE");
        verify(clusterMapper).updateById(any(Cluster.class));
    }

    @Test
    void 更新_不改名称时不触发重名检查() {
        when(clusterMapper.selectOne(any())).thenReturn(cluster());
        when(clusterMapper.selectCount(any())).thenReturn(0L);

        SaveClusterRequest request = new SaveClusterRequest();
        request.setClusterName("生产集群A");
        service.update("CL-20261007-0001", request);

        // ne(id, 自己) 把自身排除在外，否则"编辑但没改名"会被自己判重名
        verify(clusterMapper).selectCount(any());
    }
}
