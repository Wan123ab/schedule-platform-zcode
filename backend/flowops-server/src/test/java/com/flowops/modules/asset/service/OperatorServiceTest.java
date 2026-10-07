package com.flowops.modules.asset.service;

import com.flowops.common.exception.BizException;
import com.flowops.common.util.IdGen;
import com.flowops.domain.entity.asset.Operator;
import com.flowops.domain.entity.project.Project;
import com.flowops.domain.mapper.asset.OperatorMapper;
import com.flowops.domain.mapper.asset.OperatorVersionMapper;
import com.flowops.domain.mapper.project.ProjectMapper;
import com.flowops.modules.asset.converter.OperatorConverterImpl;
import com.flowops.modules.asset.dto.OperatorVO;
import com.flowops.modules.asset.dto.SaveOperatorRequest;
import com.flowops.modules.governance.scope.ScopeGuard;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 算子服务单测（docs/07 §4.2 的 42211 闸门 + D-27 业务编号翻译）。
 *
 * <p>用真实的 {@link OperatorConverterImpl}（MapStruct 生成物）而不是 mock：
 * 转换器的 ignore 列表正是"哪些字段必须由 Service 补齐"的契约，mock 掉它
 * 就等于把这条契约从测试里删掉。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperatorServiceTest {

    @Mock private OperatorMapper operatorMapper;
    @Mock private OperatorVersionMapper versionMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private IdGen idGen;

    private OperatorService service;

    @BeforeEach
    void setUp() {
        when(idGen.next(anyString(), anyString())).thenReturn("OP-0007");
        service = new OperatorService(operatorMapper, versionMapper, projectMapper, idGen,
                new OperatorConverterImpl(), new ScopeGuard());
    }

    private Project project() {
        Project project = new Project();
        project.setId(11L);
        project.setProjectId("PRJ-20261007-0001");
        project.setProjectName("电商数仓");
        project.setDeleted(false);
        return project;
    }

    /**
     * 出参补齐走的是 {@code selectById}（按内部主键回查项目名），与入参解析用的
     * {@code selectOne}（按业务编号）是两条路径 —— 只桩前者会让 VO 里的项目字段静默为 null。
     */
    private void givenProjectResolvable() {
        when(projectMapper.selectOne(any())).thenReturn(project());
        when(projectMapper.selectById(11L)).thenReturn(project());
    }

    private Operator operator() {
        Operator operator = new Operator();
        operator.setId(21L);
        operator.setOperatorId("OP-20261007-0001");
        operator.setOperatorName("订单清洗");
        operator.setOperatorType("JAR");
        operator.setProjectId(11L);
        operator.setStatus("ENABLED");
        operator.setVersionCount(2);
        operator.setDeleted(false);
        return operator;
    }

    private SaveOperatorRequest request() {
        SaveOperatorRequest request = new SaveOperatorRequest();
        request.setOperatorName("订单清洗");
        request.setOperatorType("JAR");
        request.setProjectId("PRJ-20261007-0001");
        request.setDescription("日增量清洗");
        return request;
    }

    // ── 创建 ────────────────────────────────────────────────

    @Test
    void 创建_编号取全局递增式且初始状态为启用() {
        givenProjectResolvable();
        when(operatorMapper.selectCount(any())).thenReturn(0L);

        OperatorVO vo = service.create(request());

        assertThat(vo.getOperatorId()).isEqualTo("OP-0007");
        assertThat(vo.getStatus()).isEqualTo("ENABLED");
        assertThat(vo.getVersionCount()).isZero();
        assertThat(vo.getProjectId()).isEqualTo("PRJ-20261007-0001");   // 出网是业务编号（D-27）
        assertThat(vo.getProjectName()).isEqualTo("电商数仓");
        verify(idGen).next("OP", "op");
        verify(operatorMapper).insert(any(Operator.class));
    }

    @Test
    void 创建_同项目下重名_拒绝() {
        when(projectMapper.selectOne(any())).thenReturn(project());
        when(operatorMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("名称已存在");
        verify(operatorMapper, never()).insert(any(Operator.class));
    }

    @Test
    void 创建_归属项目不存在_40400() {
        when(projectMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.create(request()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40400);
    }

    @Test
    void 创建_未指定状态_默认启用() {
        givenProjectResolvable();
        when(operatorMapper.selectCount(any())).thenReturn(0L);
        SaveOperatorRequest request = request();
        request.setStatus(null);

        assertThat(service.create(request).getStatus()).isEqualTo("ENABLED");
    }

    // ── 编辑 ────────────────────────────────────────────────

    @Test
    void 编辑_更换归属项目_被拒绝() {
        when(operatorMapper.selectOne(any())).thenReturn(operator());
        SaveOperatorRequest request = request();
        request.setProjectId("PRJ-20261007-0002");
        Project other = project();
        other.setId(12L);
        other.setProjectId("PRJ-20261007-0002");
        when(projectMapper.selectOne(any())).thenReturn(other);

        assertThatThrownBy(() -> service.update("OP-20261007-0001", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("归属项目创建后不可变更");
        verify(operatorMapper, never()).updateById(any(Operator.class));
    }

    @Test
    void 编辑_停用算子_落到DISABLED() {
        when(operatorMapper.selectOne(any())).thenReturn(operator());
        givenProjectResolvable();
        when(operatorMapper.selectCount(any())).thenReturn(0L);
        SaveOperatorRequest request = request();
        request.setStatus("DISABLED");

        assertThat(service.update("OP-20261007-0001", request).getStatus()).isEqualTo("DISABLED");
    }

    // ── 删除闸门（42211）──────────────────────────────────────

    @Test
    void 删除_版本已被工作流引用_42211且不落删() {
        when(operatorMapper.selectOne(any())).thenReturn(operator());
        when(operatorMapper.countVersionReferences(21L)).thenReturn(3L);

        assertThatThrownBy(() -> service.delete("OP-20261007-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(42211);
        verify(operatorMapper, never()).softDelete(any());
        verify(versionMapper, never()).softDeleteByOperatorId(any());
    }

    @Test
    void 删除_无引用_算子与版本一并软删() {
        when(operatorMapper.selectOne(any())).thenReturn(operator());
        when(operatorMapper.countVersionReferences(21L)).thenReturn(0L);

        service.delete("OP-20261007-0001");

        // 顺序：先版本后算子（反过来的话日志里会留下"算子已删、版本还在"的中间态）
        var inOrder = org.mockito.Mockito.inOrder(versionMapper, operatorMapper);
        inOrder.verify(versionMapper).softDeleteByOperatorId(21L);
        inOrder.verify(operatorMapper).softDelete(21L);
    }

    // ── 可见性（40301 / 40400）────────────────────────────────

    /** 存在但越权 → 40301；探测只取 boolean，不复用为响应数据。 */
    @Test
    void 查询_存在但越权_40301() {
        when(operatorMapper.selectOne(any())).thenReturn(null, operator());

        assertThatThrownBy(() -> service.get("OP-20261007-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40301);
    }

    @Test
    void 查询_确实不存在_40400() {
        // 分两次写而不是 thenReturn(null, null)：后者会命中 thenReturn(T, T...) 的
        // varargs 重载，"末参为 null"导致重载解析不精确，编译期报 unchecked 告警
        when(operatorMapper.selectOne(any())).thenReturn(null).thenReturn(null);

        assertThatThrownBy(() -> service.get("OP-9999-0001"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40400);
    }

    @Test
    void 查询_可见_出参补齐项目信息() {
        when(operatorMapper.selectOne(any())).thenReturn(operator());
        when(projectMapper.selectById(11L)).thenReturn(project());

        OperatorVO vo = service.get("OP-20261007-0001");

        assertThat(vo.getProjectId()).isEqualTo("PRJ-20261007-0001");
        assertThat(vo.getProjectName()).isEqualTo("电商数仓");
        assertThat(vo.getVersionCount()).isEqualTo(2);
    }

    /** 按业务编号过滤时先翻译成内部主键，否则会把业务编号当主键查（永远查不到）。 */
    @Test
    void 列表_项目过滤_翻译成内部主键() {
        when(projectMapper.selectOne(any())).thenReturn(project());
        when(operatorMapper.selectPage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        service.page(1, 20, null, "PRJ-20261007-0001", null);

        verify(projectMapper).selectOne(any());
        verify(operatorMapper).selectPage(any(), any());
    }

    @Test
    void 需求快照更新_走单点方法() {
        Operator operator = operator();
        service.updateSnapshot(operator);

        verify(operatorMapper).updateById(eq(operator));
    }
}
