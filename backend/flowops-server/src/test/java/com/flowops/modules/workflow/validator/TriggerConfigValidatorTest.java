package com.flowops.modules.workflow.validator;

import com.flowops.common.api.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 触发器配置校验器单测（42216 / 42217 / 40001 三分流）。
 */
class TriggerConfigValidatorTest {

    private static ErrorCode codeOf(TriggerConfigValidator.Violation v) {
        return v.code();
    }

    @Test
    void CRON_只给cron表达式_合法() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", "0 0 2 * * *", null, "Asia/Shanghai", null, null);
        assertThat(v).isEmpty();
    }

    @Test
    void CRON_只给固定周期_合法() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", null, 300, "Asia/Shanghai", null, null);
        assertThat(v).isEmpty();
    }

    @Test
    void CRON_两者都给_42216二选一() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", "0 0 2 * * *", 300, null, null, null);
        assertThat(v).hasSize(1);
        assertThat(codeOf(v.get(0))).isEqualTo(ErrorCode.CRON_INVALID);
        assertThat(v.get(0).message()).contains("二选一");
    }

    @Test
    void CRON_两者都缺_42216() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", null, null, null, null, null);
        assertThat(v).hasSize(1);
        assertThat(codeOf(v.get(0))).isEqualTo(ErrorCode.CRON_INVALID);
        assertThat(v.get(0).message()).contains("必须提供");
    }

    @Test
    void CRON_表达式语法错误_42216并带格式提示() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", "not a cron", null, null, null, null);
        assertThat(v).hasSize(1);
        assertThat(codeOf(v.get(0))).isEqualTo(ErrorCode.CRON_INVALID);
        assertThat(v.get(0).message()).contains("6 段");
    }

    @Test
    void CRON_周期非正_42216() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", null, 0, null, null, null);
        assertThat(v).hasSize(1);
        assertThat(codeOf(v.get(0))).isEqualTo(ErrorCode.CRON_INVALID);
    }

    @Test
    void MANUAL_不需要cron配置_合法() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "MANUAL", null, null, null, null, null);
        assertThat(v).isEmpty();
    }

    @Test
    void 时区非法_40001() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", "0 0 2 * * *", null, "Mars/Olympus", null, null);
        assertThat(v).hasSize(1);
        assertThat(codeOf(v.get(0))).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(v.get(0).message()).contains("时区");
    }

    @Test
    void 生效窗口_end等于start_42217() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", "0 0 2 * * *", null, null,
                "2026-10-08T00:00:00+08:00", "2026-10-08T00:00:00+08:00");
        assertThat(v).hasSize(1);
        assertThat(codeOf(v.get(0))).isEqualTo(ErrorCode.TRIGGER_WINDOW_INVALID);
        assertThat(v.get(0).message()).contains("end").contains("start");
    }

    @Test
    void 生效窗口_end早于start_42217() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", "0 0 2 * * *", null, null,
                "2026-10-08T00:00:00+08:00", "2026-10-07T00:00:00+08:00");
        assertThat(codeOf(v.get(0))).isEqualTo(ErrorCode.TRIGGER_WINDOW_INVALID);
    }

    @Test
    void 生效窗口_end晚于start_合法() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", "0 0 2 * * *", null, null,
                "2026-10-08T00:00:00+08:00", "2026-10-09T00:00:00+08:00");
        assertThat(v).isEmpty();
    }

    @Test
    void 生效窗口_时间格式坏_40001而非42217() {
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", "0 0 2 * * *", null, null, "明天", null);
        assertThat(v).hasSize(1);
        assertThat(codeOf(v.get(0))).isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void 跨时区的end晚于start_合法_比较发生在时间轴上() {
        // +08:00 的 10-08 12:00 晚于 +00:00 的 10-08 06:00（= +08:00 的 14:00）……
        // 反过来写：end 在 UTC、start 在 +08，实际 end 更晚 → 合法
        List<TriggerConfigValidator.Violation> v = TriggerConfigValidator.validate(
                "CRON", "0 0 2 * * *", null, null,
                "2026-10-08T06:00:00+08:00", "2026-10-08T06:00:00Z");
        assertThat(v).isEmpty();
    }
}
