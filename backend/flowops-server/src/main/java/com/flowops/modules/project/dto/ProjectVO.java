package com.flowops.modules.project.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/** 项目出参（docs/07 §8.1：XxxVO）。 */
@Data
public class ProjectVO {

    private String projectId;
    private String projectName;
    private String description;
    private String status;
    private Integer maxConcurrentTasks;
    private Integer maxWaitingTasks;
    private String ownerUsername;
    private Integer statWorkflowCount;
    private Integer statTaskCount;
    private Integer statMemberCount;
    private OffsetDateTime createdAt;
}
