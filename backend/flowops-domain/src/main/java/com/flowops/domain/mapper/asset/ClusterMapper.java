package com.flowops.domain.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.asset.Cluster;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Set;

/**
 * 集群 Mapper（docs/05 §3.3）。
 *
 * <p>自定义 SQL 一律在 XML（docs/10 细则）；本接口只声明语义，实现见 mapper/asset/ClusterMapper.xml。</p>
 */
@Mapper
public interface ClusterMapper extends BaseMapper<Cluster> {

    /**
     * 运维人员的「被授权集群」集合（docs/07 §5.3 判定链第 4 步 AUTHORIZED_CLUSTER 的数据来源）。
     *
     * <p><b>口径说明（M2 决策，已在 README-M2 登记）</b>：docs/05 全量 DDL 中<b>没有</b>
     * user→cluster 的直接授权表，只有 R3 的 {@code project_cluster}（项目可用集群）。
     * 因此「被授权集群」在本期定义为：<b>该用户所属项目被授予的集群并集</b>。
     * 语义自洽：运维只能看到"与自己有业务关系的项目所使用的那批机器"。
     * 若后续引入独立的集群授权表，只需改本 SQL，调用方（DataScopeResolver）零改动。</p>
     */
    Set<Long> findAuthorizedClusterIds(@Param("userId") Long userId);

    /** 集群下的执行节点数（42204 删除闸门的实时 COUNT，绝不信 node_total 快照）。 */
    long countNodes(@Param("clusterId") Long clusterId);

    /** 集群下的队列数（删除集群时一并闸门：queue.cluster_id 是 RESTRICT 外键）。 */
    long countQueues(@Param("clusterId") Long clusterId);

    /** 集群下运行中的任务数（维护模式/删除前的作业面检查）。 */
    long countRunningTasks(@Param("clusterId") Long clusterId);

    /** 软删除（显式 XML：MP 的 updateById 会剔除逻辑删除列，见 XML 注释）。 */
    int softDelete(@Param("id") Long id);
}
