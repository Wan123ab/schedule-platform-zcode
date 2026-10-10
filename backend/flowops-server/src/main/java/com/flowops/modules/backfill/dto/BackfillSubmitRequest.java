package com.flowops.modules.backfill.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 回填提交入参 = 预览请求 + 确认哈希（CONTRACT §8 双闸门；42231 = 未预览 / 配置已变）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BackfillSubmitRequest extends BackfillPreviewRequest {

    /** preview 返回的 SHA-256；服务端重算比对，5 分钟 TTL（docs/07 §6.5） */
    @NotBlank(message = "confirmedPreviewHash 必填（先预览后提交）")
    private String confirmedPreviewHash;
}
