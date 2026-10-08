package com.flowops.domain.mapper.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.workflow.WorkflowVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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

    /**
     * 某工作流下的全部版本（新→旧），供版本列表使用。
     *
     * <p><b>刻意不选 JSONB 大列</b>（{@code dag_definition} / {@code workflow_params} /
     * {@code trigger_config}）：列表只展示版本号、状态、步骤数、发布人/时间，
     * 而这三列是整张图与全部参数的原样快照 —— 一个 100 节点的工作流会把历史版本
     * 的几十份完整 DAG 一次性拉进内存。要图就走 {@code GET /workflow-versions/{versionId}}。</p>
     *
     * <p>软删行必须排除（与 {@link #selectMaxVersionIndex} 正好相反）：那里是为了不撞
     * 唯一索引而必须看见已删行，这里是为了不把已丢弃的草稿展示给用户。</p>
     */
    List<WorkflowVersion> listByWorkflowId(@Param("workflowId") Long workflowId);

    /** 软删单个版本（草稿被丢弃时）。 */
    int softDelete(@Param("id") Long id);

    /** 软删某工作流下全部版本（2026-10-07 起工作流本身不提供删除接口，此方法留给运维/归档用）。 */
    int softDeleteByWorkflowId(@Param("workflowId") Long workflowId);
}
