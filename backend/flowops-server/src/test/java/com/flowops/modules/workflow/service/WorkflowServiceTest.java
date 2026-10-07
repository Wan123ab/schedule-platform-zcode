package com.flowops.modules.workflow.service;

import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.entity.workflow.Workflow;
import com.flowops.domain.entity.workflow.WorkflowVersion;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.domain.mapper.workflow.TriggerMapper;
import com.flowops.domain.mapper.workflow.WorkflowMapper;
import com.flowops.domain.mapper.workflow.WorkflowVersionMapper;
import com.flowops.modules.governance.scope.ScopeGuard;
import com.flowops.modules.workflow.converter.WorkflowConverterImpl;
import com.flowops.modules.workflow.converter.WorkflowVersionConverterImpl;
import com.flowops.modules.workflow.dto.SaveConcurrencyRequest;
import com.flowops.modules.workflow.dto.SaveWorkflowRequest;
import com.flowops.modules.workflow.dto.WorkflowVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流服务单测（CONTRACT §6.1；docs/07 §5.4 / §7.4）。
 *
 * <p>用真实的 {@code WorkflowConverterImpl}（MapStruct 生成物）而不是 mock：
 * 转换器的 ignore 列表正是"哪些字段必须由 Service 补齐"的契约，
 * mock 掉它就等于把这条契约从测试里删掉。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowVersionMapper versionMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private TriggerMapper triggerMapper;
    @Mock private IdGen idGen;
    @Mock private WorkflowVersionService versionService;

    private WorkflowAccessGuard guard;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        when(idGen.next(anyString(), anyString())).thenReturn("WF-0003");
        guard = new WorkflowAccessGuard(workflowMapper, new ScopeGuard());
        service = new WorkflowService(workflowMapper, versionMapper, projectMapper, triggerMapper, idGen,
                new WorkflowConverterImpl(), new WorkflowVersionConverterImpl(), guard, versionService);
    }

    private Project project() {
        Project project = new Project();
        project.setId(11L);
        project.setProjectId("PRJ-20261007-0001");
        project.setProjectName("电商数仓");
        project.setDeleted(false);
        return project;
    }

    /** 入参解析走 selectOne（按业务编号），出参补项目名走 selectById（按主键）—— 两条路径都要桩。 */
    private void givenProjectResolvable() {
        when(projectMapper.selectOne(any())).thenReturn(project());
        when(projectMapper.selectById(11L)).thenReturn(project());
    }

    private Workflow workflow() {
        Workflow workflow = new Workflow();
        workflow.setId(11L);
        workflow.setWorkflowId("WF-0001");
        workflow.setWorkflowName("日增量清算");
        workflow.setProjectId(11L);
        workflow.setStatus("DRAFT");
        workflow.setHasDraftChanges(false);
        workflow.setConcurrencyPolicy("FORBID");
        workflow.setMaxParallelRuns(1);
        workflow.setDeleted(false);
        return workflow;
    }

    private WorkflowVersion version(Long id, String businessId, String no, String status) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(id);
        version.setVersionId(businessId);
        version.setWorkflowId(11L);
        version.setVersionNo(no);
        version.setPublishStatus(status);
        version.setDeleted(false);
        return version;
    }

    private SaveWorkflowRequest request() {
        SaveWorkflowRequest request = new SaveWorkflowRequest();
        request.setWorkflowName("日增量清算");
        request.setProjectId("PRJ-20261007-0001");
        request.setDescription("T+1 批处理");
        return request;
    }

    // ── 创建 ────────────────────────────────────────────────

    @Test
    void 创建_编号取WF全局递增式且初始为草稿() {
        givenProjectResolvable();
        when(workflowMapper.selectCount(any())).thenReturn(0L);

        WorkflowVO vo = service.create(request());

        assertThat(vo.getWorkflowId()).isEqualTo("WF-0003");
        assertThat(vo.getStatus()).isEqualTo("DRAFT");
        // DDL 默认值必须显式写进内存实体：否则"新建后立刻返回的 VO"会与库里不一致
        assertThat(vo.getConcurrencyPolicy()).isEqualTo("FORBID");
        assertThat(vo.getMaxParallelRuns()).isEqualTo(1);
        assertThat(vo.getHasDraftChanges()).isFalse();
        assertThat(vo.getCurrentVersion()).isNull();
        assertThat(vo.getProjectId()).isEqualTo("PRJ-20261007-0001");   // 出网是业务编号（D-27）
        verify(idGen).next("WF", "wf");
        verify(workflowMapper).insert(any(Workflow.class));
    }

    @Test
    void 创建_同项目下重名_拒绝且不落库() {
        when(projectMapper.selectOne(any())).thenReturn(project());
        when(workflowMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("名称已存在");
        verify(workflowMapper, never()).insert(any(Workflow.class));
    }

    @Test
    void 创建_归属项目不存在_40400() {
        when(projectMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40400);
    }

    // ── 查询 ────────────────────────────────────────────────

    @Test
    void 详情_带上当前版本概要() {
        Workflow workflow = workflow();
        workflow.setStatus("PUBLISHED");
        workflow.setCurrentVersionId(31L);
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        when(projectMapper.selectById(11L)).thenReturn(project());
        when(versionMapper.selectById(31L)).thenReturn(version(31L, "WFV-0001-02", "v2", "PUBLISHED"));

        WorkflowVO vo = service.get("WF-0001");

        assertThat(vo.getCurrentVersion()).isNotNull();
        assertThat(vo.getCurrentVersion().getVersionId()).isEqualTo("WFV-0001-02");
        assertThat(vo.getCurrentVersion().getVersionNo()).isEqualTo("v2");
    }

    @Test
    void 列表_排序字段不在白名单_40003() {
        assertThatThrownBy(() -> service.page(1, 20, null, null, null, "workflow_id; drop table", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40003);
    }

    @Test
    void 列表_白名单内字段与默认排序_都能通过() {
        when(workflowMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        assertThat(service.page(1, 20, null, null, null, "created_at", "asc").getRecords()).isEmpty();
        assertThat(service.page(1, 20, null, null, null, null, null).getRecords()).isEmpty();
    }

    // ── 编辑 / 并发 ─────────────────────────────────────────

    @Test
    void 编辑_更换归属项目_拒绝且不更新() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow());
        SaveWorkflowRequest request = request();
        request.setProjectId("PRJ-20261007-0002");
        Project other = project();
        other.setId(12L);
        other.setProjectId("PRJ-20261007-0002");
        when(projectMapper.selectOne(any())).thenReturn(other);

        assertThatThrownBy(() -> service.update("WF-0001", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("归属项目创建后不可变更");
        verify(workflowMapper, never()).updateById(any(Workflow.class));
    }

    @Test
    void 并发设置_写入策略与并行数() {
        Workflow workflow = workflow();
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        when(projectMapper.selectById(11L)).thenReturn(project());
        SaveConcurrencyRequest request = new SaveConcurrencyRequest();
        request.setConcurrencyPolicy("QUEUE");
        request.setMaxParallelRuns(3);

        WorkflowVO vo = service.updateConcurrency("WF-0001", request);

        assertThat(vo.getConcurrencyPolicy()).isEqualTo("QUEUE");
        assertThat(vo.getMaxParallelRuns()).isEqualTo(3);
        verify(workflowMapper).updateById(workflow);
    }

    // ── 发布 / 停用 ─────────────────────────────────────────

    @Test
    void 发布_校验先于指针切换且清掉草稿标记() {
        Workflow workflow = workflow();
        workflow.setHasDraftChanges(true);
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        when(projectMapper.selectById(11L)).thenReturn(project());
        WorkflowVersion published = version(31L, "WFV-0001-02", "v2", "PUBLISHED");
        when(versionService.publishVersion(workflow, "WFV-0001-02")).thenReturn(published);
        // 出参组装不回声请求，而是按 current_version_id 回读一次 —— 这次回读必须能被桩到，
        // 否则"响应里的当前版本"会是 null（正是本测试第一次跑出来的样子）
        when(versionMapper.selectById(31L)).thenReturn(published);

        WorkflowVO vo = service.publish("WF-0001", "WFV-0001-02");

        // 顺序：先让版本侧跑全量校验并冻结版本，再切 current_version —— 反过来会有一段
        // "指针已指向未发布版本"的中间态（事务能回滚，但并发的读会看到）
        var order = inOrder(versionService, workflowMapper);
        order.verify(versionService).publishVersion(workflow, "WFV-0001-02");
        order.verify(workflowMapper).updateById(workflow);

        assertThat(vo.getStatus()).isEqualTo("PUBLISHED");
        assertThat(vo.getCurrentVersion().getVersionId()).isEqualTo("WFV-0001-02");
        assertThat(workflow.getHasDraftChanges()).isFalse();
        assertThat(workflow.getCurrentVersionId()).isEqualTo(31L);
    }

    @Test
    void 发布_版本校验不通过_异常向上抛且不更新工作流() {
        Workflow workflow = workflow();
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        when(versionService.publishVersion(workflow, "WFV-0001-01"))
                .thenThrow(new BizException(com.flowops.common.api.ErrorCode.DAG_VALIDATE_FAILED,
                        "步骤「A」未选择算子或算子版本"));

        assertThatThrownBy(() -> service.publish("WF-0001", "WFV-0001-01"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未选择算子");
        verify(workflowMapper, never()).updateById(any(Workflow.class));
    }

    @Test
    void 停用_未发布状态_40900() {
        when(workflowMapper.selectOne(any())).thenReturn(workflow());   // DRAFT

        assertThatThrownBy(() -> service.disable("WF-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40900);
    }

    @Test
    void 停用_已发布_转DISABLED() {
        Workflow workflow = workflow();
        workflow.setStatus("PUBLISHED");
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        when(projectMapper.selectById(11L)).thenReturn(project());

        assertThat(service.disable("WF-0001").getStatus()).isEqualTo("DISABLED");
        // 停用联动：该工作流的触发器一并置 enabled=false（docs/07 §6.4），软状态不恢复
        verify(triggerMapper).disableByWorkflowId(workflow.getId());
    }

    // ── 越权语义（40301 / 40400）─────────────────────────────

    @Test
    void 查询_越权_40301_而不是40400() {
        // 第一次（带行级过滤）查不到；探测（无过滤）时能查到 → 存在但越权
        when(workflowMapper.selectOne(any())).thenReturn(null, workflow());

        assertThatThrownBy(() -> service.get("WF-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40301);
    }

    @Test
    void 查询_确实不存在_40400() {
        when(workflowMapper.selectOne(any())).thenReturn(null, null);

        assertThatThrownBy(() -> service.get("WF-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40400);
    }

    @Test
    void 按主键查询_越权与不存在_分别40301与40400() {
        when(workflowMapper.selectById(99L)).thenReturn(null, workflow());
        assertThatThrownBy(() -> service.requireVisibleById(99L))
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40301);
    }

    @Test
    void 名称唯一性校验_排除自身() {
        Workflow workflow = workflow();
        when(workflowMapper.selectOne(any())).thenReturn(workflow);
        givenProjectResolvable();      // update 走 selectOne 解析入参项目 + selectById 补出参项目名
        when(workflowMapper.selectCount(any())).thenReturn(0L);

        WorkflowVO vo = service.update("WF-0001", request());

        assertThat(vo.getWorkflowName()).isEqualTo("日增量清算");
        verify(workflowMapper).selectCount(any());
        verify(workflowMapper).updateById(eq(workflow));
    }
}
