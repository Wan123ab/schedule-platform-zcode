package com.flowops.modules.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 成员操作入参。 */
public class MemberRequests {

    private MemberRequests() {
    }

    @Data
    public static class AddMember {
        @NotBlank(message = "用户名必填")
        private String username;
        @NotBlank(message = "项目角色必填")
        private String projectRole;   // PROJECT_ADMIN / OPERATOR_MAINTAINER / BUSINESS
    }

    @Data
    public static class UpdateRole {
        @NotBlank(message = "项目角色必填")
        private String projectRole;
    }
}
