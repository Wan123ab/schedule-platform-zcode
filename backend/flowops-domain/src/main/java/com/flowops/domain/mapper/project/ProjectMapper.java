package com.flowops.domain.mapper.project;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowops.domain.entity.project.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 项目空间 Mapper（docs/05 §3.2）。CRUD 走 BaseMapper；
 * 停用影响面与触发器联动（docs/07 §6.1 / docs/06 §11.3）在 XML。
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    long countWorkflowsByProject(@Param("projectId") Long projectId);

    long countTriggersByProject(@Param("projectId") Long projectId);

    /** 停用闸门的 blocking[] 明细（运行中任务的业务编号，前端展示"受影响任务"）。 */
    List<String> findRunningTaskIdsByProject(@Param("projectId") Long projectId, @Param("limit") int limit);

    /**
     * 停用联动（docs/06 §11.3 ①）：记录触发器原状态后暂停 —— 重新启用时按原状态恢复。
     */
    int disableProjectTriggers(@Param("projectId") Long projectId);

    /**
     * 启用联动（docs/06 §11.3 ②）：恢复到停用前状态；next_fire_time 置 NULL
     * —— 恢复后必须重算（否则停用期间过期的触发点会被误判"错过触发"而补跑），
     * 重算由 M3 TriggerService（cron-utils）落地。
     */
    int enableProjectTriggers(@Param("projectId") Long projectId);
}
