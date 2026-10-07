package com.flowops.modules.asset.validator;

import com.flowops.common.api.FieldError;
import com.flowops.modules.asset.dto.OperatorVersionMeta;
import com.flowops.modules.asset.dto.OperatorVersionParts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 算子版本元数据校验单测（docs/07 §6.3 校验项）。
 *
 * <p>断言的重点不是"能报错"，而是<b>一次报全</b>与<b>错误字段可定位</b>：
 * 这两点直接决定上传表单的用户体验，也是把校验从注解式改为显式收集的原因。</p>
 */
class OperatorVersionValidatorTest {

    private final OperatorVersionValidator validator = new OperatorVersionValidator();

    private OperatorVersionMeta validMeta() {
        OperatorVersionMeta meta = new OperatorVersionMeta();
        meta.setStartCommand("python3 main.py --date ${bizDate}");
        meta.setOsType("LINUX");
        return meta;
    }

    private static OperatorVersionParts.ParamDef param(String key, String type) {
        OperatorVersionParts.ParamDef def = new OperatorVersionParts.ParamDef();
        def.setParamKey(key);
        def.setParamType(type);
        return def;
    }

    private static OperatorVersionParts.OutputDecl output(String varName, String mode, String expr) {
        OperatorVersionParts.OutputDecl decl = new OperatorVersionParts.OutputDecl();
        decl.setVarName(varName);
        decl.setExtractMode(mode);
        decl.setExpression(expr);
        return decl;
    }

    @Test
    void 合法元数据_无错误() {
        OperatorVersionMeta meta = validMeta();
        meta.setParamTemplate(List.of(param("inputPath", "TEXT")));
        meta.setOutputDeclarations(List.of(output("rowCount", "REGEX", "rows=(\\d+)")));

        assertThat(validator.validate(meta)).isEmpty();
    }

    @Test
    void 缺少启动命令_报错且字段可定位() {
        OperatorVersionMeta meta = validMeta();
        meta.setStartCommand("  ");

        assertThat(validator.validate(meta))
                .singleElement()
                .extracting(FieldError::field)
                .isEqualTo("startCommand");
    }

    @Test
    void meta为空_报缺失而不是抛异常() {
        assertThat(validator.validate(null))
                .singleElement()
                .extracting(FieldError::field)
                .isEqualTo("meta");
    }

    /** 一次收集全部错误 —— 用户提交一次就能看到所有要改的地方。 */
    @Test
    void 多处错误_一次全部返回() {
        OperatorVersionMeta meta = validMeta();
        meta.setStartCommand(null);
        meta.setOsType("MACOS");
        meta.setParamTemplate(List.of(param("1bad", "TEXT"), param("ok", "UNKNOWN")));
        meta.setOutputDeclarations(List.of(output("bad-name", "REGEX", null)));

        List<FieldError> errors = validator.validate(meta);

        assertThat(errors).extracting(FieldError::field)
                .contains("startCommand", "osType", "paramTemplate[0].paramKey",
                        "paramTemplate[1].paramType", "outputDeclarations[0].varName",
                        "outputDeclarations[0].expression");
    }

    @Test
    void 参数key与输出变量名_不允许点号与中划线() {
        OperatorVersionMeta meta = validMeta();
        meta.setParamTemplate(List.of(param("input.path", "TEXT")));
        meta.setOutputDeclarations(List.of(output("row-count", "REGEX", "x")));

        assertThat(validator.validate(meta)).extracting(FieldError::field)
                .containsExactlyInAnyOrder("paramTemplate[0].paramKey", "outputDeclarations[0].varName");
    }

    @Test
    void 数字开头的标识符_被拒绝() {
        OperatorVersionMeta meta = validMeta();
        meta.setParamTemplate(List.of(param("2fast", "TEXT")));

        assertThat(validator.validate(meta)).extracting(FieldError::field)
                .containsExactly("paramTemplate[0].paramKey");
    }

    /** 重复键在 DB 有唯一索引兜底，但那会抛 23505、无法告诉用户是第几行重复。 */
    @Test
    void 参数key重复_定位到重复的那一行() {
        OperatorVersionMeta meta = validMeta();
        meta.setParamTemplate(List.of(param("dup", "TEXT"), param("dup", "TEXT")));

        List<FieldError> errors = validator.validate(meta);

        assertThat(errors).singleElement().extracting(FieldError::field)
                .isEqualTo("paramTemplate[1].paramKey");
    }

    @Test
    void 输出变量名重复_定位到重复的那一行() {
        OperatorVersionMeta meta = validMeta();
        meta.setOutputDeclarations(List.of(output("dup", "REGEX", "a"), output("dup", "REGEX", "b")));

        assertThat(validator.validate(meta)).singleElement().extracting(FieldError::field)
                .isEqualTo("outputDeclarations[1].varName");
    }

    /** SINGLE 类型没有候选值，前端会渲染成一个无法选择的空下拉 —— 必须在保存前拦住。 */
    @Test
    void SINGLE类型缺少候选值_报错() {
        OperatorVersionMeta meta = validMeta();
        meta.setParamTemplate(List.of(param("mode", "SINGLE")));

        assertThat(validator.validate(meta)).extracting(FieldError::field)
                .containsExactly("paramTemplate[0].options");
    }

    @Test
    void 环境变量名重复_报错() {
        OperatorVersionMeta meta = validMeta();
        OperatorVersionParts.EnvVar a = new OperatorVersionParts.EnvVar();
        a.setKey("HADOOP_CONF_DIR");
        OperatorVersionParts.EnvVar b = new OperatorVersionParts.EnvVar();
        b.setKey("HADOOP_CONF_DIR");
        meta.setEnvVars(List.of(a, b));

        assertThat(validator.validate(meta)).singleElement().extracting(FieldError::field)
                .isEqualTo("envVars[1].key");
    }

    @Test
    void 非法提取方式与负数超时_都报错() {
        OperatorVersionMeta meta = validMeta();
        meta.setDefaultTimeoutSeconds(0);
        meta.setLogMaxBytes(-1L);
        meta.setOutputDeclarations(List.of(output("ok", "GREP", "x")));

        assertThat(validator.validate(meta)).extracting(FieldError::field)
                .containsExactlyInAnyOrder("defaultTimeoutSeconds", "logMaxBytes",
                        "outputDeclarations[0].extractMode");
    }
}
