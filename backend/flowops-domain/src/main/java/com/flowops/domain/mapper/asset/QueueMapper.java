package com.flowops.domain.mapper.asset;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.asset.Queue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 队列 Mapper（docs/05 §3.3）。自定义 SQL 见 mapper/asset/QueueMapper.xml。
 */
@Mapper
public interface QueueMapper extends BaseMapper<Queue> {

    /** 队列下非终态任务的等待数（40902 判定基准，实时 COUNT）。 */
    long countWaitingTasks(@Param("queueId") Long queueId);

    /** 队列下运行中任务数（禁用队列前的作业面检查）。 */
    long countRunningTasks(@Param("queueId") Long queueId);

    /** 软删除（显式 XML：MP 的 updateById 会剔除逻辑删除列，见 XML 注释）。 */
    int softDelete(@Param("id") Long id);
}
