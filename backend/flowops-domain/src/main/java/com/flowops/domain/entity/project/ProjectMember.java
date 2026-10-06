package com.flowops.domain.entity.project;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 项目成员（docs/05 §3.2 project_member，R2：复合主键 (project_id, user_id)）。
 * 复合主键不走 MyBatis-Plus BaseMapper —— ProjectMemberMapper 用 XML 手写四种访问形态。
 */
@Data
@TableName("project_member")
public class ProjectMember {

    private Long projectId;

    private Long userId;

    /** PROJECT_ADMIN / OPERATOR_MAINTAINER / BUSINESS ...（项目内角色） */
    private String projectRole;

    private OffsetDateTime joinedAt;

    private String addedBy;
}
