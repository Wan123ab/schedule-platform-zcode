package com.flowops.modules.governance.scope;

import com.flowops.common.api.ErrorCode;
import com.flowops.common.context.ScopeContext;
import com.flowops.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 越权语义收口（docs/07 §4.2 的 40400 与 40301；docs/09 M2 DoD「区分不存在/越权」）。
 *
 * <p><b>为什么需要它</b>：行级过滤（{@link com.flowops.config.FlowopsDataPermissionHandler}）
 * 让"越权"和"不存在"在 SQL 结果上完全一样（都是 null）。但对使用者这是两种完全不同的
 * 事实：前者是"这东西存在，只是不归你看"（应当提示权限问题，而不是误导为"已删除"）。
 * 尤其对运维人员排障场景，把 40301 显示成"资源不存在"会让人误判为数据丢失。</p>
 *
 * <p><b>判定方式</b>：可见查询（带行级过滤）取不到时，再在无过滤临界区里做一次
 * <b>存在性探测</b>（只取 boolean，不取数据）；存在 → 40301，不存在 → 40400。</p>
 */
@Slf4j
@Component
public class ScopeGuard {

    /**
     * @param resourceType 资源类型（40400/40301 的 resource_type 附带字段，docs/07 §4.2）
     * @param resourceId   资源业务编号
     * @param existsProbe  无过滤的存在性探测（返回 true 表示"存在但不可见"）
     */
    public BizException notVisible(String resourceType, String resourceId, Supplier<Boolean> existsProbe) {
        boolean exists = Boolean.TRUE.equals(ScopeContext.withoutScope(existsProbe));
        if (exists) {
            ScopeContext scope = ScopeContext.get();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("scope", scope == null ? "NONE" : scope.getType().name());
            payload.put("resource_type", resourceType);
            payload.put("resource_id", resourceId);
            log.warn("数据范围越权访问被拒 type={} id={} scope={}", resourceType, resourceId, payload.get("scope"));
            return new BizException(ErrorCode.SCOPE_EXCEEDED,
                    resourceType + " 存在但超出当前数据范围: " + resourceId, payload);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resource_type", resourceType);
        payload.put("resource_id", resourceId);
        return new BizException(ErrorCode.NOT_FOUND, resourceType + " 不存在: " + resourceId, payload);
    }
}
