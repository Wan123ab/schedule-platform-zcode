package com.flowops.modules.task.dto;

import lombok.Data;

/**
 * 诊断建议。
 *
 * <p><b>只有 {@code text}、没有 {@code action}/{@code target}（README-M4 O-45）</b>：
 * docs/06 §5.4 定义的三个动作码（REDUCE_CPU / CHANGE_QUEUE / CONTACT_OPS）全部
 * 隶属 NO_MATCHING_NODE 场景——该场景本切片不可达（见 {@link TaskDiagnosisVO} 类注释）。
 * 对排队/互斥等待给动作码就是凭空造码，违背"docs 不覆盖 → 不自造"纪律。</p>
 */
@Data
public class DiagnosisSuggestionVO {

    private String text;
}
