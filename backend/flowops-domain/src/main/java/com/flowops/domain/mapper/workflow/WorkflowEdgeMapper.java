package com.flowops.domain.mapper.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.workflow.WorkflowEdge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工作流连线 Mapper（docs/05 §3.4 workflow_edge）。
 *
 * <p>边引用步骤的<b>内部主键</b>（{@code workflow_step.id}），所以整包替换时
 * 顺序是死的：先删边 → 删步骤 → 插步骤（拿到新主键）→ 插边。
 * 反过来会在插入时撞 FK（引用到刚被删掉的步骤主键）。</p>
 */
@Mapper
public interface WorkflowEdgeMapper extends BaseMapper<WorkflowEdge> {

    /** 清掉某版本的全部连线（整包替换的第一步）。 */
    int deleteByVersionId(@Param("versionId") Long versionId);

    /** 按版本列连线。 */
    List<WorkflowEdge> listByVersionId(@Param("versionId") Long versionId);
}
