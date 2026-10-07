package com.flowops.domain.mapper.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.workflow.WorkflowVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 工作流版本 Mapper（docs/05 §3.4 workflow_version）。
 */
@Mapper
public interface WorkflowVersionMapper extends BaseMapper<WorkflowVersion> {

    /**
     * 取当前最大版本序号（用于生成 vN 与版本业务编号）。
     *
     * <p><b>必须包含软删行</b>：{@code uk_wv_workflow_no} 是"工作流 + 版本号"的部分唯一索引，
     * 而 {@code uk_wv_version_id} 是<b>全表</b>唯一。若用 MP 的 lambda 查询
     * （自动带 {@code deleted = false}），被软删的 v3 不会被计入，
     * 下一次就会再生成一个 v3 → 撞 {@code version_id} 唯一索引，报错信息与真实原因
     * （"序号没跳过已删行"）完全对不上。故这里显式用 XML 查最大序号，不加 deleted 条件。</p>
     */
    Integer selectMaxVersionIndex(@Param("workflowId") Long workflowId);

    /** 软删单个版本（草稿被丢弃时）。 */
    int softDelete(@Param("id") Long id);

    /** 软删某工作流下全部版本（2026-10-07 起工作流本身不提供删除接口，此方法留给运维/归档用）。 */
    int softDeleteByWorkflowId(@Param("workflowId") Long workflowId);
}
