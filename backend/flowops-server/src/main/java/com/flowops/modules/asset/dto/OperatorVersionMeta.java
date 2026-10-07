package com.flowops.modules.asset.dto;

import lombok.Data;

import java.util.List;

/**
 * 算子版本上传/编辑的元数据（docs/07 §6.3 的 multipart {@code meta} 部分）。
 *
 * <p><b>为什么用一段 JSON 而不是把字段拍平成多个 part</b>：本对象里有三层嵌套
 * （{@code env_vars[]}、{@code param_template[].options[]}、{@code output_declarations[]}）。
 * multipart 扁平字段表达嵌套只能靠 {@code paramTemplate[0].options[1]} 这种下标名 ——
 * 一旦把下标名写进对外契约，前端调整一个元素顺序就可能让后端绑定错位，且报错时的
 * 字段名极难阅读。用一段 JSON 后，"嵌套"由 JSON 自己表达，校验错误也能按字段路径回填。</p>
 *
 * <p><b>与 prd/CONTRACT-API §5 的关系</b>：该契约草案把上传字段写成扁平列表，未覆盖
 * 嵌套集合的表达方式；此处以 docs/07 §6.3 的实现口径为准，并登记到 docs/00 §2
 * 决策日志（D-28）。前端仍按契约发 {@code FormData}，只把嵌套部分收进 {@code meta} 字段。</p>
 */
@Data
public class OperatorVersionMeta {

    private String description;

    /** LINUX / WINDOWS（为空按 LINUX 处理） */
    private String osType;

    private String startCommand;

    private String workDir;

    private List<OperatorVersionParts.EnvVar> envVars;

    /** 成功退出码；留空默认 [0] */
    private List<Integer> successCodes;

    private List<OperatorVersionParts.ParamDef> paramTemplate;

    private List<OperatorVersionParts.OutputDecl> outputDeclarations;

    private OperatorVersionParts.DefaultResource defaultResource;

    // ── 默认值（继承链第 4 层，PRD §12.5）──
    private Integer defaultTimeoutSeconds;
    private Integer defaultRetryCount;
    private Integer defaultRetryIntervalSeconds;

    // ── 日志模式（PRD §13.1-7 大日志模式）──
    private Integer logTailLines;
    private Long logMaxBytes;
}
