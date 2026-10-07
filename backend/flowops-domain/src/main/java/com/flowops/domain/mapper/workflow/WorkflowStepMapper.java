package com.flowops.domain.mapper.workflow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.workflow.WorkflowStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工作流步骤 Mapper（docs/05 §3.4 workflow_step）。
 *
 * <p><b>为什么是"整包替换"而不是增量 upsert</b>：CONTRACT §6.2 定死了
 * "DAG 整包保存，不做步骤级增量接口"。整包替换（先删后插）换来的是
 * "保存后的表内容 == 提交的图"这一条可判定性质；增量 upsert 则会引入
 * "前端删了一个步骤但后端没收到删除指令"这类只能靠人工比对发现的漂移。</p>
 */
@Mapper
public interface WorkflowStepMapper extends BaseMapper<WorkflowStep> {

    /** 清掉某版本的全部步骤（整包替换的第一步）。 */
    int deleteByVersionId(@Param("versionId") Long versionId);

    /** 按版本列步骤（保存时回读、详情页渲染画布都要）。 */
    List<WorkflowStep> listByVersionId(@Param("versionId") Long versionId);
}
