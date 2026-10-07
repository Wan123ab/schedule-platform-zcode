package com.flowops.modules.workflow.validator;

import com.flowops.common.api.ErrorCode;
import org.springframework.scheduling.support.CronExpression;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 触发器配置校验器（纯函数，docs/07 §4.2 的 42216 / 42217）。
 *
 * <p><b>错误码分流</b>：cron 表达式/周期配置的任何问题 → 42216（含"二选一"约束 ——
 * 它是 cron 配置完整性的一部分，拆成别的码会让前端多处理一类弹窗）；
 * 生效窗口 {@code end <= start} → 42217；时区/时间格式不可解析 → 40001（参数非法，
 * 不是"触发器业务规则"问题）。</p>
 *
 * <p><b>cron 方言</b>：采用 Spring {@link CronExpression}（6 段，秒开头）——
 * 调度引擎就是 Spring 生态，用 Quartz 7 段方言反而要在调度侧再翻译一层。
 * Quartz 风格的表达式会被 42216 拒绝，报错信息里带解析器的原始说明。</p>
 *
 * <p>纯函数：不注入任何东西，CRUD 之外（未来调度器补跑校验、前端表单即时校验
 * 的服务端镜像）都能直接复用。</p>
 */
public final class TriggerConfigValidator {

    /** 一条校验失败。code 决定 HTTP 状态与错误码，message 直出给前端。 */
    public record Violation(ErrorCode code, String message) {
    }

    private TriggerConfigValidator() {
    }

    /**
     * 校验触发器配置。
     *
     * @param triggerType    MANUAL / CRON（API / EVENT 由 Service 拒绝，不进本方法）
     * @param cronExpression cron 表达式，可空
     * @param periodSeconds  固定周期秒数，可空
     * @param timezone       IANA 时区，可空（空 = 用 DDL 默认 Asia/Shanghai，这里不判）
     * @param effectiveStart 生效窗口起点（ISO-8601），可空
     * @param effectiveEnd   生效窗口终点，可空
     * @return 全部失败项；空列表 = 合法
     */
    public static List<Violation> validate(String triggerType, String cronExpression, Integer periodSeconds,
                                           String timezone, String effectiveStart, String effectiveEnd) {
        List<Violation> violations = new ArrayList<>();
        if ("CRON".equals(triggerType)) {
            boolean hasCron = cronExpression != null && !cronExpression.isBlank();
            boolean hasPeriod = periodSeconds != null;
            if (hasCron && hasPeriod) {
                violations.add(new Violation(ErrorCode.CRON_INVALID,
                        "cron 表达式与固定周期只能二选一"));
            } else if (!hasCron && !hasPeriod) {
                violations.add(new Violation(ErrorCode.CRON_INVALID,
                        "CRON 触发器必须提供 cron 表达式或固定周期（二选一）"));
            } else if (hasCron) {
                try {
                    CronExpression.parse(cronExpression.trim());
                } catch (IllegalArgumentException e) {
                    violations.add(new Violation(ErrorCode.CRON_INVALID,
                            "cron 表达式非法：" + firstLine(e.getMessage())
                            + "（采用 Spring 6 段格式，秒 分 时 日 月 周）"));
                }
            } else if (periodSeconds <= 0) {
                // Bean 校验已拦 @Min(1)，这里是纯函数自身的完整性（直接调用方不走 Bean 校验）
                violations.add(new Violation(ErrorCode.CRON_INVALID,
                        "固定周期必须为正整数秒，实际 " + periodSeconds));
            }
        }
        if (timezone != null && !timezone.isBlank()) {
            try {
                ZoneId.of(timezone.trim());
            } catch (Exception e) {
                violations.add(new Violation(ErrorCode.PARAM_INVALID,
                        "时区非法：" + timezone + "（需 IANA 名称，如 Asia/Shanghai）"));
            }
        }
        boolean hasStart = effectiveStart != null && !effectiveStart.isBlank();
        boolean hasEnd = effectiveEnd != null && !effectiveEnd.isBlank();
        OffsetDateTime start = null;
        OffsetDateTime end = null;
        if (hasStart) {
            try {
                start = OffsetDateTime.parse(effectiveStart.trim());
            } catch (DateTimeParseException e) {
                violations.add(new Violation(ErrorCode.PARAM_INVALID,
                        "生效窗口起点不是合法的 ISO-8601 时间：" + effectiveStart));
            }
        }
        if (hasEnd) {
            try {
                end = OffsetDateTime.parse(effectiveEnd.trim());
            } catch (DateTimeParseException e) {
                violations.add(new Violation(ErrorCode.PARAM_INVALID,
                        "生效窗口终点不是合法的 ISO-8601 时间：" + effectiveEnd));
            }
        }
        if (start != null && end != null && !end.isAfter(start)) {
            violations.add(new Violation(ErrorCode.TRIGGER_WINDOW_INVALID,
                    "触发器生效窗口非法：end（" + effectiveEnd + "）必须晚于 start（" + effectiveStart + "）"));
        }
        return violations;
    }

    /** 解析异常的第一行往往就够定位；整个堆栈塞进错误消息太吵。 */
    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "无法解析";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
