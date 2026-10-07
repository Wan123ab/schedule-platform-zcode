package com.flowops.modules.workflow.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 创建/更新触发器请求（docs/07 §6.4；DDL docs/05 §3.4 trigger）。
 *
 * <p><b>cron 与固定周期二选一</b>：{@code triggerType=CRON} 时两者必须恰给其一
 * （都缺或都给 → 42216）。格式与时间窗校验在 {@code TriggerConfigValidator}（纯函数）。</p>
 *
 * <p><b>编号不出网（D-27）</b>：{@code workflowId} 用业务编号 {@code WF-xxxx}；
 * {@code lockedVersionId} / {@code targetQueueId} 一期请求不支持（锁定版本 = 跟随最新），
 * 登记为 README-M3 偏离。</p>
 */
@Data
public class SaveTriggerRequest {

    /** 父工作流业务编号（仅创建时必填；更新时不可变更 —— 挂靠关系改了语义就乱） */
    @Size(max = 32, message = "工作流编号长度超限")
    private String workflowId;

    @NotBlank(message = "触发器名称必填")
    @Size(max = 128, message = "触发器名称长度超限")
    private String triggerName;

    /** MANUAL / CRON（API / EVENT 一期置灰，docs/07 §11） */
    @NotBlank(message = "触发器类型必填")
    private String triggerType;

    /** Spring CronExpression 格式（6 段）；与 periodSeconds 二选一 */
    @Size(max = 128, message = "cron 表达式长度超限")
    private String cronExpression;

    /** 固定周期秒数（与 cronExpression 二选一） */
    @Min(value = 1, message = "固定周期必须为正整数秒")
    private Integer periodSeconds;

    /** IANA 时区；缺省 Asia/Shanghai（DDL 默认值） */
    @Size(max = 64, message = "时区长度超限")
    private String timezone;

    /** 生效窗口起点（ISO-8601 带时区，如 2026-10-08T00:00:00+08:00）；可空 = 不限 */
    private String effectiveStart;

    /** 生效窗口终点；与 start 同时给出且 end <= start → 42217 */
    private String effectiveEnd;

    /** 触发时附加参数（覆盖链第 4 层的来源） */
    private Map<String, Object> runParams;

    /** 缺省 true（DDL 默认值） */
    private Boolean enabled;

    /** 停机补跑开关，缺省 true（DDL 默认值） */
    private Boolean catchUpEnabled;

    /** 补跑上限，缺省 3（DDL 默认值） */
    @Min(value = 0, message = "补跑上限不能为负")
    private Integer catchUpMaxTimes;
}
