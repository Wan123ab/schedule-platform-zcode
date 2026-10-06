package com.flowops.modules.project.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.auth.AppUser;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.entity.project.ProjectMember;
import com.flowops.domain.mapper.auth.AppUserMapper;
import com.flowops.domain.mapper.concurrency.ConcurrencyQueryMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.domain.mapper.project.ProjectMemberMapper;
import com.flowops.modules.project.dto.MemberRequests;
import com.flowops.modules.project.dto.ProjectImpactVO;
import com.flowops.modules.project.dto.ProjectVO;
import com.flowops.modules.project.dto.SaveProjectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 项目空间服务（M2 资产域；docs/07 §6.1 / PRD §10.2）。
 *
 * <p><b>停用是软停用</b>（§6.1 语义）：不删数据、不中断运行中任务；闸门是
 * "运行中任务数 > 0 → 42203"（附 blocking[] 明细）；停用后触发器联动暂停
 * （docs/06 §11.3，重新启用按原状态恢复）。</p>
 *
 * <p><b>冗余计数纪律（docs/05 §6.3）</b>：stat_* 只用于展示；删除/停用等业务判定
 * 一律实时 count，绝不读快照列。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private static final DateTimeFormatter PRJ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final AppUserMapper appUserMapper;
    private final ConcurrencyQueryMapper concurrencyQuery;
    private final StringRedisTemplate redis;

    // ── 查询 ────────────────────────────────────────────────

    public Page<ProjectVO> page(long page, long size, String keyword) {
        Page<Project> result = projectMapper.selectPage(new Page<>(page, Math.min(size, 200)),
                Wrappers.<Project>lambdaQuery()
                        .eq(Project::getDeleted, false)
                        .like(keyword != null && !keyword.isBlank(), Project::getProjectName, keyword)
                        .orderByDesc(Project::getCreatedAt));
        return result.convert(this::toVO);
    }

    public ProjectVO get(String projectId) {
        return toVO(requireByBusinessId(projectId));
    }

    // ── 写操作 ──────────────────────────────────────────────

    @Transactional(rollbackFor = Exception.class)
    public ProjectVO create(SaveProjectRequest request) {
        var currentUser = com.flowops.common.context.UserContext.get();
        Project project = new Project();
        project.setProjectId(nextProjectId());
        project.setProjectName(request.getProjectName());
        project.setDescription(request.getDescription());
        project.setStatus("ENABLED");
        project.setDefaultParams(toJson(request.getDefaultParams()));
        project.setOwnerUserId(currentUser != null ? currentUser.getUserId() : null);
        project.setMaxConcurrentTasks(5);    // PRD §11.4 建议默认，项目管理员可调
        project.setMaxWaitingTasks(50);
        project.setStatWorkflowCount(0);
        project.setStatTaskCount(0);
        project.setStatMemberCount(0);
        projectMapper.insert(project);

        // 创建者自动成为项目管理员（PRD §11.2：项目管理员可管本项目）
        if (currentUser != null) {
            AppUser owner = appUserMapper.selectById(currentUser.getUserId());
            if (owner != null) {
                addMemberRow(project.getId(), owner.getId(), "PROJECT_ADMIN", currentUser.getUsername());
                project.setStatMemberCount(1);
                projectMapper.updateById(project);
            }
        }
        log.info("项目已创建 project={} name={}", project.getProjectId(), project.getProjectName());
        return toVO(project);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectVO update(String projectId, SaveProjectRequest request) {
        Project project = requireByBusinessId(projectId);
        project.setProjectName(request.getProjectName());
        project.setDescription(request.getDescription());
        project.setDefaultParams(toJson(request.getDefaultParams()));
        projectMapper.updateById(project);
        return toVO(project);
    }

    /** 停用影响面（docs/07 §6.1 GET /impact：前端 PUT status 前必查）。 */
    public ProjectImpactVO impact(String projectId) {
        Project project = requireByBusinessId(projectId);
        List<String> blocking = projectMapper.findRunningTaskIdsByProject(project.getId(), 20);
        return new ProjectImpactVO(
                projectMapper.countWorkflowsByProject(project.getId()),
                concurrencyQuery.countRunningTasksByProject(project.getId()),
                projectMemberMapper.countByProject(project.getId()),
                projectMapper.countTriggersByProject(project.getId()),
                blocking);
    }

    /** 启停闸门（PRD §10.2 / docs/07 §6.1）：42203 + blocking[]；触发器联动 docs/06 §11.3。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String projectId, String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅允许 ENABLED|DISABLED");
        }
        Project project = requireByBusinessId(projectId);
        if (status.equals(project.getStatus())) {
            return;   // 幂等
        }
        if ("DISABLED".equals(status)) {
            long running = concurrencyQuery.countRunningTasksByProject(project.getId());
            if (running > 0) {
                // 闸门：实时 count（绝不读 stat_* 快照，docs/05 §6.3 纪律）
                throw new BizException(ErrorCode.PROJECT_HAS_RUNNING_TASKS, "项目停用前存在运行中任务",
                        Map.of("blocking", projectMapper.findRunningTaskIdsByProject(project.getId(), 20)));
            }
            projectMapper.disableProjectTriggers(project.getId());
        } else {
            projectMapper.enableProjectTriggers(project.getId());
        }
        project.setStatus(status);
        projectMapper.updateById(project);
        log.info("项目状态变更 project={} → {}", projectId, status);
    }

    // ── 成员管理（docs/07 §6.1 / CONTRACT §2）────────────────

    @Transactional(rollbackFor = Exception.class)
    public void addMember(String projectId, MemberRequests.AddMember request) {
        Project project = requireByBusinessId(projectId);
        AppUser user = appUserMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getUsername, request.getUsername()).eq(AppUser::getDeleted, false));
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + request.getUsername());
        }
        if (projectMemberMapper.existsByProjectAndUser(project.getId(), user.getId())) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "用户已是项目成员");
        }
        var operator = com.flowops.common.context.UserContext.get();
        addMemberRow(project.getId(), user.getId(), request.getProjectRole(),
                operator != null ? operator.getUsername() : "system");
        refreshMemberStat(project);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateMemberRole(String projectId, Long userId, String projectRole) {
        Project project = requireByBusinessId(projectId);
        guardOwnerRoleChange(project, userId);
        if (projectMemberMapper.updateRole(project.getId(), userId, projectRole) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "成员不存在");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeMember(String projectId, Long userId) {
        Project project = requireByBusinessId(projectId);
        guardOwnerRoleChange(project, userId);   // 42201：负责人不可移除（CONTRACT §2）
        if (projectMemberMapper.delete(project.getId(), userId) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "成员不存在");
        }
        refreshMemberStat(project);
    }

    /** 负责人的角色不可改、人不可移（42201，PROJECT_OWNER_UNREMOVABLE）。 */
    private void guardOwnerRoleChange(Project project, Long userId) {
        if (userId.equals(project.getOwnerUserId())) {
            throw new BizException(ErrorCode.PROJECT_OWNER_UNREMOVABLE,
                    "项目负责人不可移除或变更角色（CONTRACT §2）");
        }
    }

    private void addMemberRow(Long projectId, Long userId, String role, String addedBy) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setProjectRole(role);
        member.setAddedBy(addedBy);
        projectMemberMapper.insert(member);
    }

    private void refreshMemberStat(Project project) {
        project.setStatMemberCount((int) projectMemberMapper.countByProject(project.getId()));
        projectMapper.updateById(project);
    }

    private Project requireByBusinessId(String projectId) {
        Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
                .eq(Project::getProjectId, projectId).eq(Project::getDeleted, false));
        if (project == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在: " + projectId);
        }
        return project;
    }

    private ProjectVO toVO(Project project) {
        ProjectVO vo = new ProjectVO();
        vo.setProjectId(project.getProjectId());
        vo.setProjectName(project.getProjectName());
        vo.setDescription(project.getDescription());
        vo.setStatus(project.getStatus());
        vo.setMaxConcurrentTasks(project.getMaxConcurrentTasks());
        vo.setMaxWaitingTasks(project.getMaxWaitingTasks());
        vo.setStatWorkflowCount(project.getStatWorkflowCount());
        vo.setStatTaskCount(project.getStatTaskCount());
        vo.setStatMemberCount(project.getStatMemberCount());
        vo.setCreatedAt(project.getCreatedAt());
        if (project.getOwnerUserId() != null) {
            AppUser owner = appUserMapper.selectById(project.getOwnerUserId());
            vo.setOwnerUsername(owner != null ? owner.getUsername() : null);
        }
        return vo;
    }

    /** 业务编号 PRJ-yyyyMMdd-####（Redis INCR，docs/05 §6.2；uk_project_project_id 兜底）。 */
    private String nextProjectId() {
        String date = PRJ_DATE.format(java.time.LocalDate.now());
        Long seq = redis.opsForValue().increment("flowops:seq:prj:" + date);
        return "PRJ-" + date + "-" + (seq != null ? seq : System.currentTimeMillis() % 100000);
    }

    private String toJson(Map<String, Object> params) {
        try {
            return JSON.writeValueAsString(params == null ? Map.of() : params);
        } catch (Exception e) {
            return "{}";
        }
    }
}
