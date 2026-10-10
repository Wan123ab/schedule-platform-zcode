package com.flowops.domain.mapper.task;

import com.flowops.domain.dto.query.EtaStatsRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 调度诊断读模型（docs/06 §5.5；SQL 见 XML）。 */
@Mapper
public interface DiagnosisQueryMapper {

    /**
     * 队列近 7 天中位排队时长 + 样本数（ETA 估算输入，docs/06 §5.5）。
     *
     * <p>server 请求线程调用；{@code task} 已登记数据权限（project/cluster 两维度），
     * 注入条件是裸表名限定，故本查询<b>禁止给 task 起别名</b>（与 TaskQueryService 同一口径）。
     * 统计随请求者可见范围收窄是可接受的：ETA 本就是估算，且不泄露他人数据。</p>
     */
    EtaStatsRow etaStats(@Param("queueId") long queueId);
}
