package com.flowops.modules.asset.validator;

import com.flowops.common.api.FieldError;
import com.flowops.modules.asset.dto.OperatorVersionMeta;
import com.flowops.modules.asset.dto.OperatorVersionParts;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 算子版本元数据校验（docs/07 §6.3 的校验项，产出 {@code errors[]} 供 42210 回填）。
 *
 * <p><b>两条纪律</b>：</p>
 * <ol>
 *   <li><b>一次收集全部错误</b>，不是"发现即抛" —— 上传是一个长表单，报一个改一个
 *       会让用户提交五六次才能通过；</li>
 *   <li><b>字段路径可定位</b>：错误字段写成 {@code outputDeclarations[2].varName}，
 *       前端才能直接定位到第 3 行输入框标红，而不是弹一句"参数不合法"。</li>
 * </ol>
 *
 * <p><b>为什么这里也校验 paramKey</b>：docs/07 §6.3 只点名了
 * {@code output_declarations[].var_name}，但 paramKey 会被 M3 的变量解析器以
 * {@code ${step.X.params.key}} 的形式引用，同一个标识符约束若不一起卡住，
 * 会出现"输出名合法、参数名非法"的不对称缺口 —— 解析器照样解析不了。</p>
 */
@Component
public class OperatorVersionValidator {

    /** 变量名/参数键的统一约束（docs/07 §6.3 对 var_name 的规定，此处复用到 param_key） */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private static final Set<String> PARAM_TYPES = Set.of("TEXT", "NUMBER", "BOOLEAN", "SINGLE", "DATETIME");
    private static final Set<String> EXTRACT_MODES = Set.of("REGEX", "FILE");
    private static final Set<String> OS_TYPES = Set.of("LINUX", "WINDOWS");

    public List<FieldError> validate(OperatorVersionMeta meta) {
        List<FieldError> errors = new ArrayList<>();
        if (meta == null) {
            errors.add(FieldError.of("meta", "缺少 meta 元数据"));
            return errors;
        }

        if (isBlank(meta.getStartCommand())) {
            errors.add(FieldError.of("startCommand", "启动命令必填"));
        }
        if (meta.getOsType() != null && !OS_TYPES.contains(meta.getOsType())) {
            errors.add(FieldError.of("osType", "操作系统类型只能是 LINUX 或 WINDOWS"));
        }
        if (meta.getDefaultTimeoutSeconds() != null && meta.getDefaultTimeoutSeconds() <= 0) {
            errors.add(FieldError.of("defaultTimeoutSeconds", "默认超时必须是正数"));
        }
        if (meta.getLogMaxBytes() != null && meta.getLogMaxBytes() <= 0) {
            errors.add(FieldError.of("logMaxBytes", "日志上限必须是正数"));
        }

        validateEnvVars(meta.getEnvVars(), errors);
        validateParams(meta.getParamTemplate(), errors);
        validateOutputs(meta.getOutputDeclarations(), errors);
        return errors;
    }

    private void validateEnvVars(List<OperatorVersionParts.EnvVar> envVars, List<FieldError> errors) {
        if (envVars == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < envVars.size(); i++) {
            OperatorVersionParts.EnvVar env = envVars.get(i);
            String field = "envVars[" + i + "].key";
            if (env == null || isBlank(env.getKey())) {
                errors.add(FieldError.of(field, "环境变量名必填"));
            } else if (!seen.add(env.getKey())) {
                errors.add(FieldError.of(field, "环境变量名重复: " + env.getKey()));
            }
        }
    }

    private void validateParams(List<OperatorVersionParts.ParamDef> params, List<FieldError> errors) {
        if (params == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < params.size(); i++) {
            OperatorVersionParts.ParamDef param = params.get(i);
            String field = "paramTemplate[" + i + "].paramKey";
            if (param == null || isBlank(param.getParamKey())) {
                errors.add(FieldError.of(field, "参数 key 必填"));
                continue;
            }
            if (!IDENTIFIER.matcher(param.getParamKey()).matches()) {
                errors.add(FieldError.of(field, "参数 key 只能由字母、数字、下划线组成且不以数字开头"));
            }
            if (!seen.add(param.getParamKey())) {
                // DB 有 uk_param_def_key 兜底，但那条错误是 23505，无法告诉用户是第几行重复
                errors.add(FieldError.of(field, "参数 key 重复: " + param.getParamKey()));
            }
            if (param.getParamType() != null && !PARAM_TYPES.contains(param.getParamType())) {
                errors.add(FieldError.of("paramTemplate[" + i + "].paramType", "参数类型取值非法"));
            }
            if ("SINGLE".equals(param.getParamType())
                    && (param.getOptions() == null || param.getOptions().isEmpty())) {
                errors.add(FieldError.of("paramTemplate[" + i + "].options", "SINGLE 类型必须提供候选值"));
            }
        }
    }

    private void validateOutputs(List<OperatorVersionParts.OutputDecl> outputs, List<FieldError> errors) {
        if (outputs == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < outputs.size(); i++) {
            OperatorVersionParts.OutputDecl output = outputs.get(i);
            String field = "outputDeclarations[" + i + "].varName";
            if (output == null || isBlank(output.getVarName())) {
                errors.add(FieldError.of(field, "输出变量名必填"));
                continue;
            }
            if (!IDENTIFIER.matcher(output.getVarName()).matches()) {
                // 名字里出现点/中划线会在变量表达式里与路径分隔符冲突，
                // 那时解析器只能报"引用无效"，而错误现场离声明处已经很远
                errors.add(FieldError.of(field, "输出变量名只能由字母、数字、下划线组成且不以数字开头"));
            }
            if (!seen.add(output.getVarName())) {
                errors.add(FieldError.of(field, "输出变量名重复: " + output.getVarName()));
            }
            if (isBlank(output.getExpression())) {
                errors.add(FieldError.of("outputDeclarations[" + i + "].expression", "提取表达式必填"));
            }
            if (output.getExtractMode() != null && !EXTRACT_MODES.contains(output.getExtractMode())) {
                errors.add(FieldError.of("outputDeclarations[" + i + "].extractMode",
                        "提取方式只能是 REGEX 或 FILE"));
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
