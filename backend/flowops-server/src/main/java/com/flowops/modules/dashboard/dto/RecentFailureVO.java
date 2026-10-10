package com.flowops.modules.dashboard.dto;

import com.flowops.domain.dto.query.DashboardFailureRow;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 最近失败任务精简卡（CONTRACT §11 task[] ≤10）。 */
@Data
public class RecentFailureVO {

    private String taskId;

    private String workflowName;

    private String status;

    private LocalDate bizDate;

    private String failReason;

    private OffsetDateTime updatedAt;

    public static RecentFailureVO of(DashboardFailureRow row) {
        RecentFailureVO vo = new RecentFailureVO();
        vo.setTaskId(row.getTaskId());
        vo.setWorkflowName(row.getWorkflowName());
        vo.setStatus(row.getStatus());
        vo.setBizDate(row.getBizDate());
        vo.setFailReason(row.getFailReason());
        vo.setUpdatedAt(row.getUpdatedAt());
        return vo;
    }
}
