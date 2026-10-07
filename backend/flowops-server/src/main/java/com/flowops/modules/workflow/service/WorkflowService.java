package com.flowops.modules.workflow.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.UserContext;
import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.entity.workflow.WorkflowVersion;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.domain.mapper.workflow.TriggerMapper;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.domain.mapper.workflow.WorkflowVersionMapper;
import com.flowops.modules.workflow.converter.WorkflowConverter;
import com.flowops.modules.workflow.converter.WorkflowVersionConverter;
import com.flowops.modules.workflow.dto.SaveConcurrencyRequest;
import com.flowops.modules.workflow.dto.SaveWorkflowRequest;
import com.flowops.modules.workflow.dto.WorkflowVO;
import com.flowops.modules.workflow.dto.WorkflowVersionBrief;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 工作流服务（docs/05 §3.4 {@code workflow}；接口映射 CONTRACT §6.1 / docs/07 §5.4）。
 *
 * <p><b>红线</b>：</p>
 * <ul>
 *   <li>并发配置（{@code concurrency_policy} / {@code max_parallel_runs}）是 DAG 规则 8 的
 *       校验对象（"必须声明并发控制配置"），故单独一个端点 + 独立审计动作
 *       {@code UPDATE_CONCURRENCY}，不与基础信息编辑混在一起；</li>
 *   <li>发布 = <b>切换 {@code current_version_id} + 置工作流为 PUBLISHED + 清草稿标记</b>，
 *       三件事必须同一个事务；DAG 全量校验在版本侧完成（见
 *       {@link WorkflowVersionService#publishVersion}）；</li>
 *   <li>{@code has_draft_changes} 只有两个写点：版本侧"新开草稿/保存草稿"置 {@code true}、
 *       本类 {@link #publish} 置 {@code false}。都只改这一个布尔列。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    /**
     * 列表排序白名单（docs/07 §7.4：「每端点显式声明」；不在表内 → 40003）。
     *
     * <p>用白名单而不是"直接拼列名"的原因很朴素：{@code order_by} 进的是 SQL 片段，
     * 一旦允许任意列名就等于把 ORDER BY 交给调用方。</p>
     */
    private static final Map<String, SFunction<Workflow, Object>> SORTABLE = Map.of(
            "updated_at", Workflow::getUpdatedAt,
            "created_at", Workflow::getCreatedAt,
            "last_run_at", Workflow::getLastRunAt,
            "workflow_name", Workflow::getWorkflowName,
            "status", Workflow::getStatus);

    private static final String DEFAULT_SORT = "updated_at";

    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper versionMapper;
    private final ProjectMapper projectMapper;
    private final TriggerMapper triggerMapper;
    private final IdGen idGen;
    private final WorkflowConverter converter;
    private final WorkflowVersionConverter versionConverter;
    private final WorkflowAccessGuard guard;
    private final WorkflowVersionService versionService;

    // ── 查询 ────────────────────────────────────────────────

    public IPage<WorkflowVO> page(long page, long size, String projectBusinessId, String status,
                                  String keyword, String orderBy, String orderDir) {
        Long projectId = projectBusinessId == null || projectBusinessId.isBlank()
                ? null : resolveProjectId(projectBusinessId);
        String sortKey = orderBy == null || orderBy.isBlank() ? DEFAULT_SORT : orderBy.trim();
        SFunction<Workflow, Object> sortColumn = SORTABLE.get(sortKey);
        if (sortColumn == null) {
            throw new BizException(ErrorCode.SORT_NOT_ALLOWED, "排序字段不在白名单: " + orderBy,
                    Map.of("allowed", SORTABLE.keySet()));
        }
        boolean asc = "asc".equalsIgnoreCase(orderDir == null ? "desc" : orderDir.trim());

        Page<Workflow> result = workflowMapper.selectPage(new Page<>(page, Math.min(size, 200)),
                Wrappers.<Workflow>lambdaQuery()
                        .eq(Workflow::getDeleted, false)
                        .eq(status != null && !status.isBlank(), Workflow::getStatus, status)
                        .eq(projectId != null, Workflow::getProjectId, projectId)
                        .like(keyword != null && !keyword.isBlank(), Workflow::getWorkflowName, keyword)
                        .orderBy(true, asc, sortColumn));
        return result.convert(this::toVO);
    }

    public WorkflowVO get(String workflowId) {
        return toVO(guard.requireVisible(workflowId));
    }

    // ── 写操作 ──────────────────────────────────────────────

    /** 新建（必审动作 CREATE_WORKFLOW）：编号 {@code WF-####}（docs/05 §6.2），初始状态 DRAFT。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowVO create(SaveWorkflowRequest request) {
        Long projectId = resolveProjectId(request.getProjectId());
        requireNameAvailable(projectId, request.getWorkflowName(), null);

        Workflow workflow = new Workflow();
        workflow.setWorkflowId(idGen.next("WF", "wf"));
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setProjectId(projectId);
        workflow.setDescription(request.getDescription());
        workflow.setStatus("DRAFT");
        workflow.setCurrentVersionId(null);            // 尚未发布任何版本
        workflow.setHasDraftChanges(false);
        // DDL 默认值同步写在这里：规则 8 要求并发配置非空，靠 DB 默认值虽然也能过，
        // 但"新建后立刻返回的 VO"会与库里不一致（VO 由内存实体组装），必须显式赋值
        workflow.setConcurrencyPolicy("FORBID");
        workflow.setMaxParallelRuns(1);
        workflow.setClusterAffinityEnabled(false);
        workflow.setCreator(currentUsername());
        workflow.setVersion(0);
        workflow.setDeleted(false);
        workflowMapper.insert(workflow);
        log.info("工作流已创建 workflow={} name={}", workflow.getWorkflowId(), workflow.getWorkflowName());
        return toVO(workflow);
    }

    /**
     * 编辑基础信息（必审动作清单里无对应项，故不加 {@code @Audited}：
     * docs/07 §7.3 工作流域只有 CREATE/SAVE_DRAFT/PUBLISH/DISABLE/DELETE/UPDATE_CONCURRENCY）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowVO update(String workflowId, SaveWorkflowRequest request) {
        Workflow workflow = guard.requireVisible(workflowId);
        Long requestedProjectId = resolveProjectId(request.getProjectId());
        if (!requestedProjectId.equals(workflow.getProjectId())) {
            // 与算子同一条理由：换项目等于把可能正在被引用的编排搬出原项目边界
            throw new BizException(ErrorCode.PARAM_INVALID, "工作流归属项目创建后不可变更",
                    Map.of("rule", "WORKFLOW_PROJECT_IMMUTABLE",
                            "current_project_id", request.getProjectId()));
        }
        requireNameAvailable(workflow.getProjectId(), request.getWorkflowName(), workflow.getId());
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflowMapper.updateById(workflow);
        log.info("工作流基础信息已更新 workflow={}", workflowId);
        return toVO(workflow);
    }

    /** 并发设置（必审动作 UPDATE_CONCURRENCY）。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowVO updateConcurrency(String workflowId, SaveConcurrencyRequest request) {
        Workflow workflow = guard.requireVisible(workflowId);
        workflow.setConcurrencyPolicy(request.getConcurrencyPolicy());
        workflow.setMaxParallelRuns(request.getMaxParallelRuns());
        workflowMapper.updateById(workflow);
        log.info("工作流并发配置已更新 workflow={} policy={} maxParallel={}",
                workflowId, request.getConcurrencyPolicy(), request.getMaxParallelRuns());
        return toVO(workflow);
    }

    /**
     * 发布版本（必审动作 PUBLISH_WORKFLOW；幂等端点，见 {@code @Idempotent(required=true)}）。
     *
     * <p>顺序是刻意的：<b>先跑全量 DAG 校验并冻结版本，再切换 current_version</b>。
     * 反过来会先切换指针、再被校验打回 —— 事务能回滚，但期间任何读到 {@code current_version}
     * 的并发查询都会看到一个"指向未发布版本"的短暂状态。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowVO publish(String workflowId, String versionId) {
        Workflow workflow = guard.requireVisible(workflowId);
        WorkflowVersion version = versionService.publishVersion(workflow, versionId);

        workflow.setCurrentVersionId(version.getId());
        workflow.setStatus("PUBLISHED");
        // 发布即"把草稿落地"，未发布修改标记必须清掉：留着会让列表页一直显示"有未发布修改"，
        // 而用户刚点的就是发布
        workflow.setHasDraftChanges(false);
        workflowMapper.updateById(workflow);
        log.info("工作流已发布 workflow={} version={} status=PUBLISHED", workflowId, versionId);
        return toVO(workflow);
    }

    /** 停用（必审动作 DISABLE_WORKFLOW）：只有已发布的工作流可停用。 */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowVO disable(String workflowId) {
        Workflow workflow = guard.requireVisible(workflowId);
        if (!"PUBLISHED".equals(workflow.getStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "只有已发布的工作流可以停用，当前状态: " + workflow.getStatus(),
                    Map.of("current_status", workflow.getStatus()));
        }
        workflow.setStatus("DISABLED");
        workflowMapper.updateById(workflow);
        // 联动停触发器（docs/07 §6.4"触发器自动置 enabled=false"）。
        // 直接用 TriggerMapper 而不是 TriggerService：后者依赖本类，反向注入成环。
        // enabled_before_disable 记住原状态，恢复时按原状态还原（与项目级启停同一约定）。
        triggerMapper.disableByWorkflowId(workflow.getId());
        log.info("工作流已停用 workflow={}", workflowId);
        return toVO(workflow);
    }

    // ── 供版本服务复用 ──────────────────────────────────────

    public Workflow requireVisible(String workflowId) {
        return guard.requireVisible(workflowId);
    }

    public Workflow findById(Long id) {
        return guard.findById(id);
    }

    public Workflow requireVisibleById(Long id) {
        return guard.requireVisibleById(id);
    }

    /** 出参组装（版本服务发布后也要返回同一个 {@code workflow} 形态，故开成 public）。 */
    public WorkflowVO toVO(Workflow workflow) {
        WorkflowVO vo = converter.toVO(workflow);
        if (workflow.getProjectId() != null) {
            Project project = projectMapper.selectById(workflow.getProjectId());
            if (project != null) {
                vo.setProjectId(project.getProjectId());
                vo.setProjectName(project.getProjectName());
            }
        }
        if (workflow.getCurrentVersionId() != null) {
            WorkflowVersion current = versionMapper.selectById(workflow.getCurrentVersionId());
            if (current != null) {
                WorkflowVersionBrief brief = versionConverter.toBrief(current);
                brief.setVersionId(current.getVersionId());
                vo.setCurrentVersion(brief);
            }
        }
        return vo;
    }

    // ── 内部 ────────────────────────────────────────────────

    private Long resolveProjectId(String projectBusinessId) {
        if (projectBusinessId == null || projectBusinessId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "所属项目必填");
        }
        Project project = projectMapper.selectOne(Wrappers.<Project>lambdaQuery()
                .eq(Project::getProjectId, projectBusinessId)
                .eq(Project::getDeleted, false));
        if (project == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "归属项目不存在: " + projectBusinessId,
                    Map.of("resource_type", "PROJECT", "resource_id", projectBusinessId));
        }
        return project.getId();
    }

    /**
     * 同项目内工作流名唯一（{@code uk_workflow_project_name} 是 deleted=false 的部分唯一索引）
     * —— 先给人话错误，而不是让 DB 抛 23505。
     */
    private void requireNameAvailable(Long projectId, String name, Long excludeId) {
        Long existing = workflowMapper.selectCount(Wrappers.<Workflow>lambdaQuery()
                .eq(Workflow::getProjectId, projectId)
                .eq(Workflow::getWorkflowName, name)
                .eq(Workflow::getDeleted, false)
                .ne(excludeId != null, Workflow::getId, excludeId));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.RULE_NOT_SATISFIED, "同项目下工作流名称已存在: " + name,
                    Map.of("rule", "WORKFLOW_NAME_DUPLICATE"));
        }
    }

    private String currentUsername() {
        var ctx = UserContext.get();
        return ctx != null ? ctx.getUsername() : "system";
    }
}
