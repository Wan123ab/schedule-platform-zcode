package com.flowops.modules.project.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flowops.common.annotation.Audited;
import com.flowops.common.annotation.DataScope;
import com.flowops.common.annotation.RequiresPermission;
import com.flowops.common.api.ApiResult;
import com.flowops.common.api.PageResult;
import com.flowops.modules.project.dto.MemberRequests;
import com.flowops.modules.project.dto.ProjectImpactVO;
import com.flowops.modules.project.dto.ProjectVO;
import com.flowops.modules.project.dto.SaveProjectRequest;
import com.flowops.modules.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 项目空间接口（docs/07 §5.4 映射 / §6.1 闸门；PRD §10.2）。
 * 停用/启用、成员增删改均为必审动作（docs/07 §7.3 项目域 6 个）。
 */
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping
    @RequiresPermission("schedule:project:read")
    @DataScope({"PROJECT", "ALL"})
    public ApiResult<PageResult<ProjectVO>> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize,
                                                 @RequestParam(required = false) String keyword) {
        IPage<ProjectVO> result = projectService.page(page, pageSize, keyword);
        return ApiResult.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(),
                result.getRecords()));
    }

    @GetMapping("/{projectId}")
    @RequiresPermission("schedule:project:read")
    public ApiResult<ProjectVO> get(@PathVariable String projectId) {
        return ApiResult.ok(projectService.get(projectId));
    }

    @PostMapping
    @RequiresPermission("schedule:project:write")
    @Audited(action = "CREATE_PROJECT", targetType = "PROJECT")
    public ApiResult<ProjectVO> create(@RequestBody @Valid SaveProjectRequest request) {
        return ApiResult.ok(projectService.create(request));
    }

    @PutMapping("/{projectId}")
    @RequiresPermission("schedule:project:write")
    @Audited(action = "UPDATE_PROJECT", targetType = "PROJECT", targetIdExpr = "#projectId")
    public ApiResult<ProjectVO> update(@PathVariable String projectId,
                                       @RequestBody @Valid SaveProjectRequest request) {
        return ApiResult.ok(projectService.update(projectId, request));
    }

    /** 停用影响面（前端 PUT status 前必查，docs/07 §6.1）。 */
    @GetMapping("/{projectId}/impact")
    @RequiresPermission("schedule:project:write")
    public ApiResult<ProjectImpactVO> impact(@PathVariable String projectId) {
        return ApiResult.ok(projectService.impact(projectId));
    }

    @PutMapping("/{projectId}/status")
    @RequiresPermission("schedule:project:write")
    @Audited(action = "DISABLE_PROJECT", targetType = "PROJECT", targetIdExpr = "#projectId")
    public ApiResult<Void> updateStatus(@PathVariable String projectId,
                                        @RequestBody Map<String, String> body) {
        projectService.updateStatus(projectId, body.get("status"));
        return ApiResult.ok();
    }

    @PostMapping("/{projectId}/members")
    @RequiresPermission("schedule:project:member")
    @Audited(action = "ADD_MEMBER", targetType = "PROJECT", targetIdExpr = "#projectId")
    public ApiResult<Void> addMember(@PathVariable String projectId,
                                     @RequestBody @Valid MemberRequests.AddMember request) {
        projectService.addMember(projectId, request);
        return ApiResult.ok();
    }

    @PutMapping("/{projectId}/members/{userId}")
    @RequiresPermission("schedule:project:member")
    @Audited(action = "UPDATE_MEMBER_ROLE", targetType = "PROJECT", targetIdExpr = "#projectId")
    public ApiResult<Void> updateMemberRole(@PathVariable String projectId, @PathVariable Long userId,
                                            @RequestBody @Valid MemberRequests.UpdateRole request) {
        projectService.updateMemberRole(projectId, userId, request.getProjectRole());
        return ApiResult.ok();
    }

    @DeleteMapping("/{projectId}/members/{userId}")
    @RequiresPermission("schedule:project:member")
    @Audited(action = "REMOVE_MEMBER", targetType = "PROJECT", targetIdExpr = "#projectId")
    public ApiResult<Void> removeMember(@PathVariable String projectId, @PathVariable Long userId) {
        projectService.removeMember(projectId, userId);
        return ApiResult.ok();
    }
}
