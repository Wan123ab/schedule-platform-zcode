package com.flowops.domain.resolve;

import com.flowops.domain.resolve.VariableRefParser.VariableRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 变量引用语法单测（docs/07 §9.1 的 D-20 EBNF 逐条对应）。
 *
 * <p>与 {@code DagValidatorTest} 的分工：这里只测<b>语法与拆分</b>（不需要构造 DAG），
 * 那里测"引用是否落在可达上游"。分开是因为两者的失败原因完全不同 ——
 * 一个是"你写错了"，一个是"你连错了"，提示语也该不一样。</p>
 */
class VariableRefParserTest {

    @Test
    void 解析步骤输出引用_拆出步骤名与变量名() {
        List<VariableRef> refs = VariableRefParser.parse("${step.数据清洗.output.row_count}");

        assertThat(refs).hasSize(1);
        VariableRef ref = refs.get(0);
        assertThat(ref.valid()).isTrue();
        assertThat(ref.source()).isEqualTo("step");
        assertThat(ref.stepName()).isEqualTo("数据清洗");
        assertThat(ref.varName()).isEqualTo("row_count");
        assertThat(ref.isStepOutput()).isTrue();
    }

    @Test
    void 步骤名含特殊字符_用引号包裹() {
        VariableRef ref = VariableRefParser.parse("${step.\"步骤-2\".output.result}").get(0);

        assertThat(ref.valid()).isTrue();
        assertThat(ref.stepName()).isEqualTo("步骤-2");
        assertThat(ref.varName()).isEqualTo("result");
    }

    @Test
    void 步骤名含特殊字符却不加引号_报错并提示加引号() {
        VariableRef ref = VariableRefParser.parse("${step.步骤-2.output.result}").get(0);

        assertThat(ref.valid()).isFalse();
        assertThat(ref.error()).contains("需用引号包裹");
    }

    @Test
    void 未知来源_报错并列出可用来源() {
        VariableRef ref = VariableRefParser.parse("${whatever.x}").get(0);

        assertThat(ref.valid()).isFalse();
        assertThat(ref.error()).contains("未知的变量来源 'whatever'").contains("step");
    }

    @Test
    void 非步骤类来源_合法但不参与可达性判定() {
        List<VariableRef> refs = VariableRefParser.parse(
                "${trigger.fire_time} ${project.param.biz_date} ${platform.base_dir}");

        assertThat(refs).hasSize(3);
        assertThat(refs).allSatisfy(r -> {
            assertThat(r.valid()).isTrue();
            assertThat(r.isStepOutput()).isFalse();
            assertThat(r.stepName()).isNull();
        });
    }

    @Test
    void 缺少output段_报错() {
        VariableRef ref = VariableRefParser.parse("${step.清洗.row_count}").get(0);

        assertThat(ref.valid()).isFalse();
        assertThat(ref.error()).contains("缺少 .output. 段");
    }

    @Test
    void 未闭合的花括号也要报() {
        // 编辑中途最常见的手误：少一个右括号。若不报，用户会以为"这个引用没生效"，
        // 而不是"我少打了个括号"
        List<VariableRef> refs = VariableRefParser.parse("${step.清洗.output.row_count");

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).error()).contains("未闭合");
    }

    @Test
    void 同一段文本里的多个引用都被抽出() {
        List<VariableRef> refs = VariableRefParser.parse(
                "cp ${step.A.output.file_path} ${step.B.output.dir}/");

        assertThat(refs).extracting(VariableRef::stepName).containsExactly("A", "B");
    }

    @Test
    void 递归遍历嵌套结构_数组与对象里的引用都不漏() {
        List<VariableRef> refs = VariableRefParser.parseParams(Map.of(
                "args", List.of("--in", "${step.A.output.file_path}"),
                "env", Map.of("DATE", "${project.param.biz_date}")));

        assertThat(refs).extracting(VariableRef::source).containsExactlyInAnyOrder("step", "project");
    }

    @Test
    void 空文本与null_返回空列表() {
        assertThat(VariableRefParser.parse(null)).isEmpty();
        assertThat(VariableRefParser.parse("")).isEmpty();
        assertThat(VariableRefParser.parse("没有引用的普通文本")).isEmpty();
        assertThat(VariableRefParser.parseParams(null)).isEmpty();
    }
    @Test
    void 裸引用_无前缀的标识符合法() {
        // docs/03 §4.4 的平台变量写法（${taskId}）—— 与 §9.1 EBNF 的矛盾按"都支持"落定
        var refs = VariableRefParser.parse("${taskId}");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).valid()).isTrue();
        assertThat(refs.get(0).isBare()).isTrue();
        assertThat(refs.get(0).varName()).isEqualTo("taskId");
    }

    @Test
    void 裸引用_中文名合法() {
        var refs = VariableRefParser.parse("${任务编号}");
        assertThat(refs.get(0).valid()).isTrue();
        assertThat(refs.get(0).isBare()).isTrue();
        assertThat(refs.get(0).varName()).isEqualTo("任务编号");
    }

    @Test
    void 裸引用_含非法字符报错() {
        var refs = VariableRefParser.parse("${a-b}");
        assertThat(refs.get(0).valid()).isFalse();
        assertThat(refs.get(0).error()).contains("裸引用");
    }
}
