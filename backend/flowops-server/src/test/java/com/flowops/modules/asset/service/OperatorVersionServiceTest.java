package com.flowops.modules.asset.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.api.FieldError;
import com.flowops.common.exception.BizException;
import com.flowops.domain.dto.query.OperatorReferenceRow;
import com.flowops.domain.entity.asset.Operator;
import com.flowops.domain.entity.asset.OperatorOutputDecl;
import com.flowops.domain.entity.asset.OperatorParamDef;
import com.flowops.domain.entity.asset.OperatorVersion;
import com.flowops.domain.mapper.asset.OperatorMapper;
import com.flowops.domain.mapper.asset.OperatorOutputDeclMapper;
import com.flowops.domain.mapper.asset.OperatorParamDefMapper;
import com.flowops.domain.mapper.asset.OperatorVersionMapper;
import com.flowops.modules.asset.converter.OperatorVersionConverterImpl;
import com.flowops.modules.asset.dto.OperatorReferenceVO;
import com.flowops.modules.asset.dto.OperatorVersionVO;
import com.flowops.modules.asset.validator.OperatorVersionValidator;
import com.flowops.modules.governance.scope.ScopeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 算子版本服务单测（docs/05 §3.3 状态机 + docs/07 §6.3 上传契约 + docs/07 §4.2 错误码）。
 *
 * <p><b>为什么落盘用真实实现</b>：{@code OperatorFileStorage} 只依赖一个临时根目录，
 * mock 掉它会让"校验失败时到底有没有写盘"这条最关键的断言失去意义 —— 那正是
 * "先全部校验、再落盘"这个顺序的存在理由。</p>
 *
 * <p><b>为什么真实验证器与转换器</b>：两者的行为（错误字段路径、ignore 列表）
 * 本身就是被测契约的一部分；mock 掉等于把契约从测试里删掉，测试仍然会绿。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperatorVersionServiceTest {

    @Mock private OperatorVersionMapper versionMapper;
    @Mock private OperatorParamDefMapper paramDefMapper;
    @Mock private OperatorOutputDeclMapper outputDeclMapper;
    @Mock private OperatorMapper operatorMapper;
    @Mock private OperatorService operatorService;

    @TempDir
    Path tempDir;

    private OperatorVersionService service;

    /** 只给启动命令的最小 meta：同时也回归"其余字段留空走默认值不报错"。 */
    private static final String MINIMAL_META = """
            {"startCommand":"python job.py"}""";

    private static final String FULL_META = """
            {
              "description": "日增量清洗",
              "osType": "LINUX",
              "startCommand": "python job.py --date ${bizDate}",
              "workDir": "/opt/jobs",
              "envVars": [{"key": "MODE", "value": "prod", "secret": false}],
              "successCodes": [0, 1],
              "defaultResource": {"cpu": 2, "memory": 4096},
              "defaultTimeoutSeconds": 3600,
              "defaultRetryCount": 2,
              "defaultRetryIntervalSeconds": 60,
              "logTailLines": 2000
            }
            """;

    /** 参数/输出声明单独一份，便于断言子表的"先删后插"与 seq 补号。 */
    private static final String PARTS_META = """
            {
              "startCommand": "python job.py",
              "paramTemplate": [
                {"name": "业务日期", "paramKey": "bizDate", "paramType": "TEXT", "required": true},
                {"name": "分区", "paramKey": "part", "paramType": "SINGLE", "options": ["a", "b"], "seq": 9}
              ],
              "outputDeclarations": [
                {"varName": "rowCount", "extractMode": "REGEX", "expression": "rows=(\\\\d+)",
                 "valueType": "NUMBER", "exampleValue": "1200", "required": true}
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        service = new OperatorVersionService(versionMapper, paramDefMapper, outputDeclMapper, operatorMapper,
                operatorService, new OperatorFileStorage(tempDir.toString(), 500),
                new OperatorVersionValidator(), new OperatorVersionConverterImpl(),
                new ObjectMapper(), new ScopeGuard());
        // 组装出参时必定回查两张子表：默认给空集合，避免每个用例重复打桩
        when(paramDefMapper.listByVersionId(any())).thenReturn(List.of());
        when(outputDeclMapper.listByVersionId(any())).thenReturn(List.of());
    }

    // ── 测试夹具 ────────────────────────────────────────────

    private Operator operator() {
        Operator operator = new Operator();
        operator.setId(21L);
        operator.setOperatorId("OP-0003");
        operator.setOperatorName("订单清洗");
        operator.setOperatorType("PYTHON");
        operator.setProjectId(11L);
        operator.setStatus("ENABLED");
        operator.setVersionCount(0);
        operator.setDeleted(false);
        return operator;
    }

    private OperatorVersion version(String versionId, String versionNo, String status) {
        OperatorVersion version = new OperatorVersion();
        version.setId(31L);
        version.setVersionId(versionId);
        version.setOperatorId(21L);
        version.setVersionNo(versionNo);
        version.setPublishStatus(status);
        version.setIsDefaultVersion(false);
        version.setSuccessCodes(new Integer[]{0});
        version.setEnvVars("[{\"key\":\"MODE\",\"value\":\"prod\",\"secret\":false}]");
        version.setDefaultResource("{\"cpu\":2,\"memory\":4096}");
        version.setVersion(0);
        version.setDeleted(false);
        return version;
    }

    private static MultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/octet-stream",
                content.getBytes(StandardCharsets.UTF_8));
    }

    /** 让 insert 回填自增主键：子表的 operator_version_id 必须挂在这上面，不能是 null。 */
    private void givenInsertAssignsId(long id) {
        when(versionMapper.insert(any(OperatorVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, OperatorVersion.class).setId(id);
            return 1;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<FieldError> errorsOf(BizException ex) {
        return (List<FieldError>) ((Map<String, Object>) ex.getPayload()).get("errors");
    }

    private static int codeOf(Throwable ex) {
        return ((BizException) ex).getErrorCode().getCode();
    }

    // ── 查询 ────────────────────────────────────────────────

    @Test
    void 列表_按算子取版本_出参用业务编号并补齐算子名() {
        when(operatorService.requireVisible("OP-0003")).thenReturn(operator());
        when(versionMapper.selectList(any())).thenReturn(List.of(version("OPV-0003-01", "v1", "DRAFT")));

        List<OperatorVersionVO> list = service.listByOperator("OP-0003");

        assertThat(list).singleElement().satisfies(vo -> {
            assertThat(vo.getVersionId()).isEqualTo("OPV-0003-01");
            assertThat(vo.getOperatorId()).isEqualTo("OP-0003");   // 内部主键不出网（D-27）
            assertThat(vo.getOperatorName()).isEqualTo("订单清洗");
        });
        // 列表页不渲染参数/输出，故不查两张子表（避免列表接口 N+1）
        verify(paramDefMapper, never()).listByVersionId(any());
    }

    @Test
    void 详情_版本不存在_40400() {
        when(versionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.get("OPV-0003-99"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(40400));
    }

    /**
     * 版本表没有 project_id 列，行级过滤覆盖不到它 —— 必须借父算子判定，
     * 否则任何登录用户都能按编号读到别的项目的算子包元数据。
     */
    @Test
    void 详情_版本可见性回父算子判定_越权40301() {
        when(versionMapper.selectOne(any())).thenReturn(version("OPV-0003-01", "v1", "DRAFT"));
        when(operatorService.requireVisibleById(21L))
                .thenThrow(new BizException(ErrorCode.SCOPE_EXCEEDED, "算子存在但超出当前数据范围"));

        assertThatThrownBy(() -> service.get("OPV-0003-01"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(40301));
    }

    // ── 上传 ────────────────────────────────────────────────

    @Test
    void 上传_最小meta_默认值落库且状态为草稿() {
        when(operatorService.requireVisible("OP-0003")).thenReturn(operator());
        givenInsertAssignsId(31L);
        when(versionMapper.selectMaxVersionIndex(21L)).thenReturn(0);

        OperatorVersionVO vo = service.upload("OP-0003", file("job.py", "print(1)"), MINIMAL_META);

        ArgumentCaptor<OperatorVersion> captor = ArgumentCaptor.forClass(OperatorVersion.class);
        verify(versionMapper).insert(captor.capture());
        OperatorVersion saved = captor.getValue();
        assertThat(saved.getVersionNo()).isEqualTo("v1");
        assertThat(saved.getVersionId()).isEqualTo("OPV-0003-01");
        assertThat(saved.getPublishStatus()).isEqualTo("DRAFT");
        assertThat(saved.getIsDefaultVersion()).isFalse();
        assertThat(saved.getOsType()).isEqualTo("LINUX");                       // 留空按 LINUX
        assertThat(saved.getSuccessCodes()).containsExactly(0);                 // 留空默认 {0}
        assertThat(saved.getLogMaxBytes()).isEqualTo(104857600L);               // PRD §13.1-7 的 100MB
        assertThat(saved.getEnvVars()).isEqualTo("[]");
        assertThat(saved.getFilePath()).startsWith("OP-0003/").endsWith(".py");
        assertThat(saved.getFileChecksum()).hasSize(64);
        // 落盘真实发生，且出参的字节数与磁盘一致
        assertThat(Files.exists(tempDir.resolve(saved.getFilePath()))).isTrue();
        assertThat(vo.getVersionNo()).isEqualTo("v1");
    }

    /**
     * docs/07 §6.3 要求 42210 + errors[] 一次回填：文件与 meta 的问题必须<b>合并</b>，
     * 且此时不得落盘（否则目录里会出现来路不明的包）。
     */
    @Test
    void 上传_文件与meta错误合并为一个42210_且不落盘() {
        when(operatorService.requireVisible("OP-0003")).thenReturn(operator());
        String meta = """
                {"startCommand":"","outputDeclarations":[{"varName":"ok"},{"varName":"1bad","expression":"x"}]}""";

        assertThatThrownBy(() -> service.upload("OP-0003", file("job.rar", "x"), meta))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    assertThat(codeOf(ex)).isEqualTo(42210);
                    assertThat(errorsOf((BizException) ex)).extracting(FieldError::field)
                            .containsExactlyInAnyOrder("file", "startCommand",
                                    "outputDeclarations[0].expression", "outputDeclarations[1].varName");
                });
        verify(versionMapper, never()).insert(any(OperatorVersion.class));
        assertThat(tempDir).isEmptyDirectory();      // 校验阶段零副作用
    }

    /** meta 拼坏了与"字段没填"是同一类问题（修同一个表单），故并入 42210 而非 40002。 */
    @Test
    void 上传_meta非法JSON_并入42210而非40002() {
        when(operatorService.requireVisible("OP-0003")).thenReturn(operator());

        assertThatThrownBy(() -> service.upload("OP-0003", file("job.py", "x"), "{not json"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    assertThat(codeOf(ex)).isEqualTo(42210);
                    assertThat(errorsOf((BizException) ex)).extracting(FieldError::field).containsExactly("meta");
                });
    }

    @Test
    void 上传_meta缺失_42210() {
        when(operatorService.requireVisible("OP-0003")).thenReturn(operator());

        assertThatThrownBy(() -> service.upload("OP-0003", file("job.py", "x"), "   "))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(42210));
        assertThat(tempDir).isEmptyDirectory();
    }

    /** 重复上传同一份文件：告诉用户它已经是哪一版，比静默新建一个同内容版本有用得多。 */
    @Test
    void 上传_同算子内同内容_提示已是哪一版() {
        when(operatorService.requireVisible("OP-0003")).thenReturn(operator());
        when(versionMapper.findByChecksum(eq(21L), anyString()))
                .thenReturn(version("OPV-0003-02", "v2", "PUBLISHED"));

        assertThatThrownBy(() -> service.upload("OP-0003", file("job.py", "print(1)"), MINIMAL_META))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("v2")
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(42210));
        verify(versionMapper, never()).insert(any(OperatorVersion.class));
    }

    /**
     * 版本号永不复用：软删过 v3 之后新建的必须算 v4，否则编号会与已删行撞全表唯一索引
     * {@code uk_ov_version_id}，报一个与用户操作完全无关的 23505。
     */
    @Test
    void 上传_版本序号含软删行续号_不复用已删编号() {
        when(operatorService.requireVisible("OP-0003")).thenReturn(operator());
        givenInsertAssignsId(31L);
        when(versionMapper.selectMaxVersionIndex(21L)).thenReturn(3);

        service.upload("OP-0003", file("job.py", "print(1)"), MINIMAL_META);

        ArgumentCaptor<OperatorVersion> captor = ArgumentCaptor.forClass(OperatorVersion.class);
        verify(versionMapper).insert(captor.capture());
        assertThat(captor.getValue().getVersionNo()).isEqualTo("v4");
        assertThat(captor.getValue().getVersionId()).isEqualTo("OPV-0003-04");
        verify(versionMapper).selectMaxVersionIndex(21L);      // 不是 count（会漏掉软删行）
    }

    /** 参数与输出是同生命周期快照，采用整包替换；显式 seq 优先，缺省按提交顺序补号。 */
    @Test
    void 上传_参数与输出先删后插_且挂版本行主键() {
        when(operatorService.requireVisible("OP-0003")).thenReturn(operator());
        givenInsertAssignsId(31L);

        service.upload("OP-0003", file("job.py", "print(1)"), PARTS_META);

        verify(paramDefMapper).deleteByVersionId(31L);
        verify(outputDeclMapper).deleteByVersionId(31L);

        ArgumentCaptor<OperatorParamDef> params = ArgumentCaptor.forClass(OperatorParamDef.class);
        verify(paramDefMapper, times(2)).insert(params.capture());
        assertThat(params.getAllValues()).extracting(OperatorParamDef::getOperatorVersionId)
                .containsOnly(31L);
        assertThat(params.getAllValues().get(0).getSeq()).isZero();          // 缺省补号
        assertThat(params.getAllValues().get(1).getSeq()).isEqualTo(9);      // 显式序号优先
        assertThat(params.getAllValues().get(0).getName()).isEqualTo("业务日期");
        assertThat(params.getAllValues().get(1).getOptions()).isEqualTo("[\"a\",\"b\"]");
        assertThat(params.getAllValues().get(0).getRuntimeOverridable()).isTrue();  // 默认运行时可覆盖

        ArgumentCaptor<OperatorOutputDecl> outputs = ArgumentCaptor.forClass(OperatorOutputDecl.class);
        verify(outputDeclMapper).insert(outputs.capture());
        assertThat(outputs.getValue().getOperatorVersionId()).isEqualTo(31L);
        assertThat(outputs.getValue().getVarName()).isEqualTo("rowCount");
        assertThat(outputs.getValue().getExpression()).isEqualTo("rows=(\\d+)");
    }

    /** 快照列只用于展示，但也要维护：否则列表页的版本数永远显示 0。 */
    @Test
    void 上传_刷新算子快照_版本计数现算() {
        Operator operator = operator();
        when(operatorService.requireVisible("OP-0003")).thenReturn(operator);
        givenInsertAssignsId(31L);
        when(versionMapper.selectCount(any())).thenReturn(5L);

        service.upload("OP-0003", file("job.py", "print(1)"), MINIMAL_META);

        ArgumentCaptor<Operator> captor = ArgumentCaptor.forClass(Operator.class);
        verify(operatorService).updateSnapshot(captor.capture());
        assertThat(captor.getValue().getVersionCount()).isEqualTo(5);
        assertThat(captor.getValue().getLatestVersion()).isNull();   // 草稿不上最新版本号
    }

    // ── 编辑草稿 ─────────────────────────────────────────────

    /** 已发布版本被改会让"历史任务当时执行的是哪份代码"永久不可追溯，故只有草稿可编辑。 */
    @Test
    void 编辑草稿_非草稿_42212且不落更新() {
        when(versionMapper.selectOne(any())).thenReturn(version("OPV-0003-01", "v1", "PUBLISHED"));

        assertThatThrownBy(() -> service.updateDraft("OPV-0003-01", MINIMAL_META))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(42212));
        verify(versionMapper, never()).updateById(any(OperatorVersion.class));
    }

    @Test
    void 编辑草稿_通过_子表整包替换() {
        OperatorVersion draft = version("OPV-0003-01", "v1", "DRAFT");
        when(versionMapper.selectOne(any())).thenReturn(draft);

        OperatorVersionVO vo = service.updateDraft("OPV-0003-01", FULL_META);

        assertThat(draft.getStartCommand()).isEqualTo("python job.py --date ${bizDate}");
        assertThat(draft.getSuccessCodes()).containsExactly(0, 1);
        assertThat(draft.getDefaultTimeoutSeconds()).isEqualTo(3600);
        assertThat(draft.getPublishStatus()).isEqualTo("DRAFT");     // 编辑不改状态
        verify(versionMapper).updateById(draft);
        verify(paramDefMapper).deleteByVersionId(31L);
        verify(outputDeclMapper).deleteByVersionId(31L);
        assertThat(vo.getEnvVars()).singleElement()
                .satisfies(env -> assertThat(env.getKey()).isEqualTo("MODE"));
        assertThat(vo.getDefaultResource().getMemory()).isEqualTo(4096L);
    }

    @Test
    void 编辑草稿_meta错误_42210且不落更新() {
        when(versionMapper.selectOne(any())).thenReturn(version("OPV-0003-01", "v1", "DRAFT"));

        assertThatThrownBy(() -> service.updateDraft("OPV-0003-01", "{\"startCommand\":\"\"}"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    assertThat(codeOf(ex)).isEqualTo(42210);
                    assertThat(errorsOf((BizException) ex)).extracting(FieldError::field)
                            .containsExactly("startCommand");
                });
        verify(versionMapper, never()).updateById(any(OperatorVersion.class));
    }

    // ── 发布 / 下线 ──────────────────────────────────────────

    /** {@code uk_ov_default} 是"每算子至多一个默认版本"，故必须先清旧标记再置新默认。 */
    @Test
    void 发布_先清旧默认再置默认_并刷新算子快照() {
        OperatorVersion draft = version("OPV-0003-02", "v2", "DRAFT");
        Operator operator = operator();
        when(versionMapper.selectOne(any())).thenReturn(draft);
        when(operatorService.findById(21L)).thenReturn(operator);
        when(versionMapper.selectCount(any())).thenReturn(2L);

        OperatorVersionVO vo = service.publish("OPV-0003-02");

        var inOrder = inOrder(versionMapper);
        inOrder.verify(versionMapper).clearDefaultFlag(21L);
        inOrder.verify(versionMapper).updateById(draft);
        assertThat(draft.getPublishStatus()).isEqualTo("PUBLISHED");
        assertThat(draft.getIsDefaultVersion()).isTrue();
        assertThat(draft.getPublishedAt()).isNotNull();
        assertThat(draft.getPublisher()).isEqualTo("system");        // 无登录上下文时的兜底
        assertThat(vo.getIsDefaultVersion()).isTrue();
        // 发布后算子列表页要显示最新版本号
        ArgumentCaptor<Operator> captor = ArgumentCaptor.forClass(Operator.class);
        verify(operatorService).updateSnapshot(captor.capture());
        assertThat(captor.getValue().getLatestVersion()).isEqualTo("v2");
    }

    @Test
    void 发布_非草稿_42212() {
        when(versionMapper.selectOne(any())).thenReturn(version("OPV-0003-01", "v1", "PUBLISHED"));

        assertThatThrownBy(() -> service.publish("OPV-0003-01"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(42212));
        verify(versionMapper, never()).clearDefaultFlag(any());
    }

    /** 下线是发布后的专属动作：草稿从未上线，"下线"它说明调用方状态认知有误。 */
    @Test
    void 下线_非已发布_40900() {
        when(versionMapper.selectOne(any())).thenReturn(version("OPV-0003-01", "v1", "DRAFT"));

        assertThatThrownBy(() -> service.offline("OPV-0003-01"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(codeOf(ex)).isEqualTo(40900));
        verify(versionMapper, never()).updateById(any(OperatorVersion.class));
    }

    /** 下线后不能再是默认版本：否则新建步骤会默认引用到一个不可发布的版本。 */
    @Test
    void 下线_成功后不再是默认版本() {
        OperatorVersion published = version("OPV-0003-01", "v1", "PUBLISHED");
        published.setIsDefaultVersion(true);
        when(versionMapper.selectOne(any())).thenReturn(published);

        OperatorVersionVO vo = service.offline("OPV-0003-01");

        assertThat(published.getPublishStatus()).isEqualTo("OFFLINE");
        assertThat(published.getIsDefaultVersion()).isFalse();
        assertThat(vo.getIsDefaultVersion()).isFalse();
    }

    // ── 引用查询 ─────────────────────────────────────────────

    @Test
    void 引用查询_出参是工作流与步骤的业务编号() {
        when(versionMapper.selectOne(any())).thenReturn(version("OPV-0003-01", "v1", "PUBLISHED"));
        OperatorReferenceRow row = new OperatorReferenceRow();
        row.setWorkflowId("WF-0007");
        row.setWorkflowName("订单日批");
        row.setStepName("清洗");
        row.setVersionNo("v3");
        row.setPublishStatus("PUBLISHED");
        when(operatorMapper.findReferences(31L)).thenReturn(List.of(row));

        List<OperatorReferenceVO> references = service.references("OPV-0003-01");

        assertThat(references).singleElement().satisfies(vo -> {
            assertThat(vo.getWorkflowId()).isEqualTo("WF-0007");
            assertThat(vo.getStepName()).isEqualTo("清洗");
            assertThat(vo.getVersionNo()).isEqualTo("v3");
            assertThat(vo.getPublishStatus()).isEqualTo("PUBLISHED");
        });
        // 引用行是按版本行主键反查的，不是拿业务编号当主键
        verify(operatorMapper).findReferences(31L);
    }
}
