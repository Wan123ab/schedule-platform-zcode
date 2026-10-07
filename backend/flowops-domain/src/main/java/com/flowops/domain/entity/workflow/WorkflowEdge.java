package com.flowops.domain.entity.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 工作流连线（docs/05 §3.4 workflow_edge）—— 步骤之间的有向依赖。
 *
 * <p><b>两端指向 {@code workflow_step.id}（内部主键）而不是 {@code step_id}</b>：
 * 这是 DDL 的选择，好处是外键能真正约束"边引用的步骤确实存在"（同版本内），
 * 代价是整包保存时必须先把步骤插完拿到主键、再插边。保存顺序不能反
 * （见 {@code WorkflowVersionService#saveDraft}）。</p>
 *
 * <p><b>自环由 DB 拦</b>：{@code ck_edge_no_self_loop} 保证
 * {@code source <> target}。但<b>长环</b>（A→B→C→A）DB 管不了，
 * 那是 DAG 规则 5 的职责（Kahn 拓扑排序），且必须在发布前查。</p>
 *
 * <p><b>无 deleted 列</b>：从属快照，随版本一起整包替换 / 软删（版本软删时不清理子表，
 * 因为子表只在版本内可见，且 {@code ON DELETE CASCADE} 针对的是物理删）。</p>
 */
@Data
@TableName("workflow_edge")
public class WorkflowEdge {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 边业务编号（前端画布用它标识元素） */
    private String edgeId;

    /** 所属版本内部主键 */
    private Long workflowVersionId;

    /** 起点步骤内部主键（→ workflow_step.id） */
    private Long sourceStepId;

    /** 终点步骤内部主键（→ workflow_step.id） */
    private Long targetStepId;

    private OffsetDateTime createdAt;
}
