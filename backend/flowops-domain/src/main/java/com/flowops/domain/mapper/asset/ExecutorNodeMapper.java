package com.flowops.domain.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.asset.ExecutorNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 执行节点 Mapper（docs/05 §3.3）。自定义 SQL 见 mapper/asset/ExecutorNodeMapper.xml。
 */
@Mapper
public interface ExecutorNodeMapper extends BaseMapper<ExecutorNode> {

    /**
     * 节点上仍在执行/调度的步骤数（42205 禁用闸门，PRD §10.4）。
     *
     * <p>task_step 只记 {@code machine_ip}（无 node_id），故按 (cluster_id, machine_ip) 定位；
     * 状态取 SCHEDULING + RUNNING —— SCHEDULING 已占用节点名额（dispatch 已下发），
     * 只算 RUNNING 会漏掉"刚下发还没回执"的窗口。</p>
     */
    long countActiveStepsOnNode(@Param("clusterId") Long clusterId, @Param("machineIp") String machineIp);

    /** 软删除（显式 XML：MP 的 updateById 会剔除逻辑删除列，见 XML 注释）。 */
    int softDelete(@Param("id") Long id);
}
