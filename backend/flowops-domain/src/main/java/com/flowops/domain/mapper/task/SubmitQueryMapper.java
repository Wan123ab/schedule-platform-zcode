package com.flowops.domain.mapper.task;

import com.flowops.domain.dto.query.SubmitVersionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提交期查询（TaskSubmitService 专用；SQL 见 XML）。
 * 与调度读模型分开 —— 提交是低频写路径，查询面按提交语义裁剪。
 */
@Mapper
public interface SubmitQueryMapper {

    /**
     * 解析提交绑定的版本（D-11：任务强绑定版本快照）：
     * versionId 缺省 = 当前发布版本（w.current_version_id）；显式指定则必须已发布。
     */
    SubmitVersionRow resolveVersionForSubmit(@Param("workflowId") Long workflowId,
                                             @Param("versionId") Long versionId);

    /** 项目绑定的任意启用队列（M1 缺省队列兜底；显式 target_queue 与步骤级队列随 M3/M4 细化）。 */
    Long findAnyEnabledQueueIdByProject(@Param("projectId") Long projectId);
}
