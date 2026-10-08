package com.flowops.modules.asset.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.common.api.ErrorCode;
import com.flowops.common.api.FieldError;
import com.flowops.common.context.UserContext;
import com.flowops.common.exception.BizException;
import com.flowops.domain.entity.asset.Operator;
import com.flowops.domain.entity.asset.OperatorOutputDecl;
import com.flowops.domain.entity.asset.OperatorParamDef;
import com.flowops.domain.entity.asset.OperatorVersion;
import com.flowops.domain.mapper.asset.OperatorMapper;
import com.flowops.domain.mapper.asset.OperatorOutputDeclMapper;
import com.flowops.domain.mapper.asset.OperatorParamDefMapper;
import com.flowops.domain.mapper.asset.OperatorVersionMapper;
import com.flowops.modules.asset.converter.OperatorVersionConverter;
import com.flowops.modules.asset.dto.OperatorReferenceVO;
import com.flowops.modules.asset.dto.OperatorVersionMeta;
import com.flowops.modules.asset.dto.OperatorVersionParts;
import com.flowops.modules.asset.dto.OperatorVersionVO;
import com.flowops.modules.asset.validator.OperatorVersionValidator;
import com.flowops.modules.governance.scope.ScopeGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 算子版本服务（docs/05 §3.3 operator_version；docs/07 §6.3 上传契约）。
 *
 * <p><b>不可变性（D-11 / PRD §10.7）</b>：状态机是
 * {@code DRAFT →（publish）→ PUBLISHED →（offline）→ OFFLINE}。</p>
 * <ul>
 *   <li>只有 DRAFT 可编辑（{@link #updateDraft}），非草稿编辑一律 42212 ——
 *       工作流步骤按 {@code operator_version_id} 绑定，已发布版本被改会让
 *       "历史任务当时执行的是哪份代码"永久不可追溯；</li>
 *   <li>发布时同时把该版本置为默认版本（docs/07 §5.4：发布 → 设默认），
 *       依赖 {@code uk_ov_default} 保证每算子至多一个默认版本，故必须先清旧标记；</li>
 *   <li>版本号一旦占用<b>永不复用</b>（{@link OperatorVersionMapper#selectMaxVersionIndex}
 *       连软删行一起数）：否则版本编号会与已删行撞 {@code uk_ov_version_id}。</li>
 * </ul>
 *
 * <p><b>随版本快照的两张子表</b>（{@code operator_param_def} / {@code operator_output_decl}）
 * 采用"先删后插"的整包替换：它们是同一份契约的组成部分，增量修改只会制造
 * "参数是新的、输出是旧的"这种半更新状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorVersionService {

    private final OperatorVersionMapper versionMapper;
    private final OperatorParamDefMapper paramDefMapper;
    private final OperatorOutputDeclMapper outputDeclMapper;
    private final OperatorMapper operatorMapper;
    private final OperatorService operatorService;
    private final OperatorFileStorage fileStorage;
    private final OperatorVersionValidator validator;
    private final OperatorVersionConverter converter;
    private final ObjectMapper objectMapper;
    private final ScopeGuard scopeGuard;

    // ── 查询 ────────────────────────────────────────────────

    /** 某算子下的版本列表（算子详情页）。 */
    public List<OperatorVersionVO> listByOperator(String operatorId) {
        Operator operator = operatorService.requireVisible(operatorId);
        return versionMapper.selectList(Wrappers.<OperatorVersion>lambdaQuery()
                        .eq(OperatorVersion::getOperatorId, operator.getId())
                        .eq(OperatorVersion::getDeleted, false)
                        .orderByDesc(OperatorVersion::getId))
                .stream()
                .map(v -> assemble(v, operator, false))
                .toList();
    }

    public OperatorVersionVO get(String versionId) {
        return assemble(requireVisible(versionId), null, true);
    }

    /** 被引用列表（docs/07 §5.4 {@code GET /operator-versions/{versionId}/references}）。 */
    public List<OperatorReferenceVO> references(String versionId) {
        OperatorVersion version = requireVisible(versionId);
        return operatorMapper.findReferences(version.getId()).stream()
                .map(row -> {
                    OperatorReferenceVO vo = new OperatorReferenceVO();
                    vo.setWorkflowId(row.getWorkflowId());
                    vo.setWorkflowName(row.getWorkflowName());
                    vo.setVersionNo(row.getVersionNo());
                    vo.setPublishStatus(row.getPublishStatus());
                    vo.setStepName(row.getStepName());
                    return vo;
                })
                .toList();
    }

    // ── 上传 / 编辑 ──────────────────────────────────────────

    /**
     * 上传新版本（必审动作 UPLOAD_VERSION）。
     *
     * <p>校验顺序刻意是"先全部校验、再落盘"：文件与 meta 的问题合成一个 42210
     * 一次回填（docs/07 §6.3 的 {@code errors[]}），用户不必按提交次数逐条发现。
     * 唯一在落盘后才做的检查是 checksum 去重 —— 那必须先算出摘要。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public OperatorVersionVO upload(String operatorId, MultipartFile file, String metaJson) {
        Operator operator = operatorService.requireVisible(operatorId);
        OperatorVersionMeta meta = parseMeta(metaJson);

        List<FieldError> errors = new ArrayList<>(fileStorage.validate(file));
        errors.addAll(validator.validate(meta));
        if (!errors.isEmpty()) {
            throw uploadInvalid(errors);
        }

        OperatorFileStorage.StoredFile stored = fileStorage.store(operator.getOperatorId(), file);

        // 同算子内同一份文件：直接告诉用户它已经是哪一版，比新建一个内容相同的版本更有用
        OperatorVersion duplicated = versionMapper.findByChecksum(operator.getId(), stored.checksum());
        if (duplicated != null) {
            throw uploadInvalid(List.of(FieldError.of("file",
                    "该文件已上传为版本 " + duplicated.getVersionNo())));
        }

        int versionIndex = versionMapper.selectMaxVersionIndex(operator.getId()) + 1;
        OffsetDateTime now = OffsetDateTime.now();

        OperatorVersion version = new OperatorVersion();
        version.setVersionId(buildVersionId(operator.getOperatorId(), versionIndex));
        version.setOperatorId(operator.getId());
        version.setVersionNo("v" + versionIndex);
        version.setDescription(meta.getDescription());
        version.setFileName(file.getOriginalFilename());
        version.setFileSize(stored.size());
        version.setFileChecksum(stored.checksum());
        version.setFilePath(stored.relativePath());
        version.setOsType(meta.getOsType() != null ? meta.getOsType() : "LINUX");
        version.setStartCommand(meta.getStartCommand());
        version.setWorkDir(meta.getWorkDir());
        version.setEnvVars(writeJson(meta.getEnvVars(), "[]"));
        version.setSuccessCodes(meta.getSuccessCodes() == null || meta.getSuccessCodes().isEmpty()
                ? new Integer[]{0} : meta.getSuccessCodes().toArray(Integer[]::new));
        version.setDefaultTimeoutSeconds(meta.getDefaultTimeoutSeconds());
        version.setDefaultRetryCount(meta.getDefaultRetryCount());
        version.setDefaultRetryIntervalSeconds(meta.getDefaultRetryIntervalSeconds());
        version.setDefaultResource(writeJson(meta.getDefaultResource(), "{}"));
        version.setLogTailLines(meta.getLogTailLines());
        // 大日志模式（PRD §13.1-7）默认 100MB，与 DDL 默认值一致
        version.setLogMaxBytes(meta.getLogMaxBytes() != null ? meta.getLogMaxBytes() : 104857600L);
        version.setPublishStatus("DRAFT");
        version.setIsDefaultVersion(false);
        version.setVersion(0);
        version.setDeleted(false);
        versionMapper.insert(version);

        replaceParams(version.getId(), meta.getParamTemplate());
        replaceOutputs(version.getId(), meta.getOutputDeclarations());
        refreshOperatorSnapshot(operator, version);

        log.info("算子版本已上传 operator={} version={} checksum={}",
                operator.getOperatorId(), version.getVersionId(), stored.checksum());
        return assemble(version, operator, true);
    }

    /** 编辑草稿版本（非 DRAFT → 42212）。文件不替换，只改运行参数与组成部件。 */
    @Transactional(rollbackFor = Exception.class)
    public OperatorVersionVO updateDraft(String versionId, String metaJson) {
        OperatorVersion version = requireVisible(versionId);
        requireDraft(version);
        OperatorVersionMeta meta = parseMeta(metaJson);

        List<FieldError> errors = validator.validate(meta);
        if (!errors.isEmpty()) {
            throw uploadInvalid(errors);
        }

        version.setDescription(meta.getDescription());
        if (meta.getOsType() != null) {
            version.setOsType(meta.getOsType());
        }
        version.setStartCommand(meta.getStartCommand());
        version.setWorkDir(meta.getWorkDir());
        version.setEnvVars(writeJson(meta.getEnvVars(), "[]"));
        if (meta.getSuccessCodes() != null && !meta.getSuccessCodes().isEmpty()) {
            version.setSuccessCodes(meta.getSuccessCodes().toArray(Integer[]::new));
        }
        version.setDefaultTimeoutSeconds(meta.getDefaultTimeoutSeconds());
        version.setDefaultRetryCount(meta.getDefaultRetryCount());
        version.setDefaultRetryIntervalSeconds(meta.getDefaultRetryIntervalSeconds());
        version.setDefaultResource(writeJson(meta.getDefaultResource(), "{}"));
        version.setLogTailLines(meta.getLogTailLines());
        if (meta.getLogMaxBytes() != null) {
            version.setLogMaxBytes(meta.getLogMaxBytes());
        }
        versionMapper.updateById(version);

        replaceParams(version.getId(), meta.getParamTemplate());
        replaceOutputs(version.getId(), meta.getOutputDeclarations());
        log.info("算子版本草稿已更新 version={}", versionId);
        return assemble(version, null, true);
    }

    /**
     * 把本次试运行的参数另存为该版本的默认值（PRD §10.6 的"后续动作"）。
     *
     * <p><b>为什么不违反版本不可变（D-11）</b>：冻结保护的是<b>文件与结构</b>
     * （参数 key/类型/必填/是否敏感、输出声明）—— 工作流步骤按
     * {@code operator_version_id} 绑定，改结构会让"历史任务当时执行的是哪份代码"
     * 不可追溯。而默认值只是<b>新建步骤时的预填值</b>：它不进任何已保存步骤的参数快照，
     * 也不改变命令模板，因此更新它不会让任何一条历史记录的含义发生变化。
     * 这也是 PRD 与原型都把它放在<b>已发布版本</b>详情页上的原因。</p>
     *
     * <p><b>敏感参数一律拒绝</b>：默认值会随 {@code GET /operator-versions/{id}} 的
     * {@code param_template[].default_value} 明文返回（工作流编辑器要预填它），
     * 让敏感参数走这条路等于把 M-07 的全链路脱敏开了一个后门。一期先<b>不允许写入</b>，
     * 而不是"写进去再在出参处打码"—— 后者会让"编辑草稿时回填的参数模板"变成
     * 一堆 {@code ***}，用户一提交就把真实默认值覆盖成了星号。彻底方案（读写两种口径：
     * 出参只给 {@code has_default} 布尔 + 独立的高权限读接口）已登记 README-M3 O-34。</p>
     *
     * <p>失败返回 <b>42210 + errors[]</b>（与上传同口径，逐字段回填到试运行表单）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public OperatorVersionVO saveDefaultParams(String versionId, Map<String, String> params) {
        OperatorVersion version = requireVisible(versionId);
        Map<String, String> requested = params == null ? Map.of() : params;
        List<FieldError> errors = new ArrayList<>();

        Map<String, OperatorParamDef> declared = new LinkedHashMap<>();
        for (OperatorParamDef def : paramDefMapper.listByVersionId(version.getId())) {
            declared.put(def.getParamKey(), def);
        }
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            OperatorParamDef def = declared.get(entry.getKey());
            if (def == null) {
                errors.add(FieldError.of("params." + entry.getKey(),
                        "参数模板中没有该参数: " + entry.getKey()));
            } else if (Boolean.TRUE.equals(def.getSensitive())) {
                errors.add(FieldError.of("params." + entry.getKey(),
                        "敏感参数不支持另存默认值（默认值会随版本详情明文返回）"));
            }
        }
        if (!errors.isEmpty()) {
            throw uploadInvalid(errors);
        }

        for (Map.Entry<String, String> entry : requested.entrySet()) {
            // updateDefaultValue 只动 default_value 一列；结构字段照旧冻结
            paramDefMapper.updateDefaultValue(version.getId(), entry.getKey(), entry.getValue());
        }
        log.info("试运行参数已另存为默认值 version={} 共 {} 项", versionId, requested.size());
        return assemble(version, null, true);
    }

    // ── 发布 / 下线 ──────────────────────────────────────────

    /** 发布（必审动作 PUBLISH_VERSION）：置 PUBLISHED 并成为该算子默认版本。 */
    @Transactional(rollbackFor = Exception.class)
    public OperatorVersionVO publish(String versionId) {
        OperatorVersion version = requireVisible(versionId);
        requireDraft(version);

        // 先清旧默认标记：uk_ov_default 是"每算子至多一个默认版本"的唯一索引，
        // 顺序反了会直接撞索引（这正是要写成两步而不是一次 update 的原因）
        versionMapper.clearDefaultFlag(version.getOperatorId());
        version.setPublishStatus("PUBLISHED");
        version.setIsDefaultVersion(true);
        version.setPublisher(currentUsername());
        version.setPublishedAt(OffsetDateTime.now());
        versionMapper.updateById(version);

        Operator operator = operatorService.findById(version.getOperatorId());
        if (operator != null) {
            refreshOperatorSnapshot(operator, version);
        }
        log.info("算子版本已发布 version={} operator_row={}", versionId, version.getOperatorId());
        return assemble(version, operator, true);
    }

    /** 下线（必审动作 OFFLINE_VERSION）：引用它的工作流此后不可再发布（DAG 规则 7 / 42218）。 */
    @Transactional(rollbackFor = Exception.class)
    public OperatorVersionVO offline(String versionId) {
        OperatorVersion version = requireVisible(versionId);
        if (!"PUBLISHED".equals(version.getPublishStatus())) {
            throw new BizException(ErrorCode.STATUS_CONFLICT,
                    "只有已发布版本可以下线，当前状态: " + version.getPublishStatus(),
                    Map.of("current_status", version.getPublishStatus()));
        }
        version.setPublishStatus("OFFLINE");
        // 下线后不能再是默认版本：默认版本是"新建步骤默认引用哪个版本"的依据，
        // 留一个已下线版本作默认会让新步骤一开始就引用到不可发布的对象
        version.setIsDefaultVersion(false);
        versionMapper.updateById(version);
        log.info("算子版本已下线 version={}", versionId);
        return assemble(version, null, true);
    }

    // ── 内部 ────────────────────────────────────────────────

    /**
     * 版本可见性：<b>借父算子判定数据范围</b>。
     *
     * <p>{@code operator_version} 没有 {@code project_id} 列，行级过滤是按
     * {@code operator.project_id} 注入的、覆盖不到版本表 —— 若只按版本号查，
     * 任何登录用户都能读到别的项目的算子包元数据（启动命令、环境变量、参数模板），
     * 属于静默越权。故此处先取版本行，再回父算子做可见性判定（40301/40400）。</p>
     */
    OperatorVersion requireVisible(String versionId) {
        OperatorVersion version = versionMapper.selectOne(Wrappers.<OperatorVersion>lambdaQuery()
                .eq(OperatorVersion::getVersionId, versionId)
                .eq(OperatorVersion::getDeleted, false));
        if (version == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "算子版本不存在: " + versionId,
                    Map.of("resource_type", "OPERATOR_VERSION", "resource_id", versionId));
        }
        operatorService.requireVisibleById(version.getOperatorId());
        return version;
    }

    private void requireDraft(OperatorVersion version) {
        if (!"DRAFT".equals(version.getPublishStatus())) {
            throw new BizException(ErrorCode.VERSION_NOT_DRAFT,
                    "非草稿版本不可编辑，当前状态: " + version.getPublishStatus(),
                    Map.of("current_status", version.getPublishStatus()));
        }
    }

    private void replaceParams(Long versionRowId, List<OperatorVersionParts.ParamDef> params) {
        paramDefMapper.deleteByVersionId(versionRowId);
        if (params == null) {
            return;
        }
        int seq = 0;
        for (OperatorVersionParts.ParamDef dto : params) {
            OperatorParamDef entity = new OperatorParamDef();
            entity.setOperatorVersionId(versionRowId);
            entity.setSeq(dto.getSeq() != null ? dto.getSeq() : seq);
            entity.setName(dto.getName() != null ? dto.getName() : dto.getParamKey());
            entity.setParamKey(dto.getParamKey());
            entity.setParamType(dto.getParamType() != null ? dto.getParamType() : "TEXT");
            entity.setRequired(Boolean.TRUE.equals(dto.getRequired()));
            entity.setDefaultValue(dto.getDefaultValue());
            entity.setRule(dto.getRule());
            entity.setHelp(dto.getHelp());
            entity.setRuntimeOverridable(!Boolean.FALSE.equals(dto.getRuntimeOverridable()));
            entity.setSensitive(Boolean.TRUE.equals(dto.getSensitive()));
            entity.setOptions(dto.getOptions() == null ? null : writeJson(dto.getOptions(), "[]"));
            entity.setCreatedAt(OffsetDateTime.now());
            paramDefMapper.insert(entity);
            seq++;
        }
    }

    private void replaceOutputs(Long versionRowId, List<OperatorVersionParts.OutputDecl> outputs) {
        outputDeclMapper.deleteByVersionId(versionRowId);
        if (outputs == null) {
            return;
        }
        int seq = 0;
        for (OperatorVersionParts.OutputDecl dto : outputs) {
            OperatorOutputDecl entity = new OperatorOutputDecl();
            entity.setOperatorVersionId(versionRowId);
            entity.setSeq(dto.getSeq() != null ? dto.getSeq() : seq);
            entity.setVarName(dto.getVarName());
            entity.setExtractMode(dto.getExtractMode() != null ? dto.getExtractMode() : "REGEX");
            entity.setExpression(dto.getExpression());
            entity.setValueType(dto.getValueType() != null ? dto.getValueType() : "TEXT");
            entity.setExampleValue(dto.getExampleValue());
            entity.setDescription(dto.getDescription());
            entity.setRequired(Boolean.TRUE.equals(dto.getRequired()));
            entity.setCreatedAt(OffsetDateTime.now());
            outputDeclMapper.insert(entity);
            seq++;
        }
    }

    /** 维护算子的展示快照列（docs/05 §6.3：冗余计数只用于展示，权威值永远现算）。 */
    private void refreshOperatorSnapshot(Operator operator, OperatorVersion version) {
        Long count = versionMapper.selectCount(Wrappers.<OperatorVersion>lambdaQuery()
                .eq(OperatorVersion::getOperatorId, operator.getId())
                .eq(OperatorVersion::getDeleted, false));
        operator.setVersionCount(count == null ? 0 : count.intValue());
        if ("PUBLISHED".equals(version.getPublishStatus())) {
            operator.setLatestVersion(version.getVersionNo());
        }
        operatorService.updateSnapshot(operator);
    }

    private OperatorVersionParts.ParamDef toParamDto(OperatorParamDef entity) {
        OperatorVersionParts.ParamDef dto = new OperatorVersionParts.ParamDef();
        dto.setName(entity.getName());
        dto.setParamKey(entity.getParamKey());
        dto.setParamType(entity.getParamType());
        dto.setRequired(entity.getRequired());
        dto.setDefaultValue(entity.getDefaultValue());
        dto.setRule(entity.getRule());
        dto.setHelp(entity.getHelp());
        dto.setRuntimeOverridable(entity.getRuntimeOverridable());
        dto.setSensitive(entity.getSensitive());
        dto.setOptions(readJsonList(entity.getOptions(), String.class));
        dto.setSeq(entity.getSeq());
        return dto;
    }

    private OperatorVersionParts.OutputDecl toOutputDto(OperatorOutputDecl entity) {
        OperatorVersionParts.OutputDecl dto = new OperatorVersionParts.OutputDecl();
        dto.setVarName(entity.getVarName());
        dto.setExtractMode(entity.getExtractMode());
        dto.setExpression(entity.getExpression());
        dto.setValueType(entity.getValueType());
        dto.setExampleValue(entity.getExampleValue());
        dto.setDescription(entity.getDescription());
        dto.setRequired(entity.getRequired());
        dto.setSeq(entity.getSeq());
        return dto;
    }

    /** 组装出参：JSON 字符串 → 类型化集合（converter 已 ignore 这些派生字段）。 */
    private OperatorVersionVO assemble(OperatorVersion version, Operator operator, boolean withParts) {
        OperatorVersionVO vo = converter.toVO(version);
        Operator resolved = operator != null ? operator : operatorService.findById(version.getOperatorId());
        if (resolved != null) {
            vo.setOperatorId(resolved.getOperatorId());
            vo.setOperatorName(resolved.getOperatorName());
        }
        vo.setEnvVars(readJsonList(version.getEnvVars(), OperatorVersionParts.EnvVar.class));
        vo.setSuccessCodes(version.getSuccessCodes() == null ? List.of() : List.of(version.getSuccessCodes()));
        vo.setDefaultResource(readJson(version.getDefaultResource(), OperatorVersionParts.DefaultResource.class));
        if (withParts) {
            vo.setParamTemplate(paramDefMapper.listByVersionId(version.getId()).stream()
                    .map(this::toParamDto).toList());
            vo.setOutputDeclarations(outputDeclMapper.listByVersionId(version.getId()).stream()
                    .map(this::toOutputDto).toList());
        }
        return vo;
    }

    /** 版本编号 {@code OPV-<算子数字段>-<两位版本序号>}（原型口径：如 OPV-0003-03）。 */
    private String buildVersionId(String operatorId, int versionIndex) {
        String digits = operatorId.replaceAll("\\D", "");
        return "OPV-" + digits + "-" + String.format("%02d", versionIndex);
    }

    /**
     * 解析 meta：JSON 本身不合法时不给"参数格式错误"（40002），而是并入 42210 的 errors[]。
     * 因为对上传方来说，"meta 拼坏了"与"start_command 没填"是同一类问题，修的是同一个表单。
     */
    private OperatorVersionMeta parseMeta(String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            throw uploadInvalid(List.of(FieldError.of("meta", "缺少 meta 元数据")));
        }
        try {
            return objectMapper.readValue(metaJson, OperatorVersionMeta.class);
        } catch (JsonProcessingException e) {
            throw uploadInvalid(List.of(FieldError.of("meta", "meta 不是合法 JSON: " + e.getOriginalMessage())));
        }
    }

    private String writeJson(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "元数据序列化失败: " + e.getMessage());
        }
    }

    private <T> List<T> readJsonList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            // 存量数据里若出现坏 JSON，宁可返回空集合也不要让查询整体 500
            log.warn("快照 JSON 解析失败，按空集合返回: {}", e.getMessage());
            return List.of();
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("快照 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private BizException uploadInvalid(List<FieldError> errors) {
        String summary = errors.stream().map(FieldError::message).findFirst().orElse("上传校验失败");
        return new BizException(ErrorCode.OPERATOR_UPLOAD_INVALID, summary, Map.of("errors", errors));
    }

    private String currentUsername() {
        var ctx = UserContext.get();
        return ctx != null ? ctx.getUsername() : "system";
    }
}
