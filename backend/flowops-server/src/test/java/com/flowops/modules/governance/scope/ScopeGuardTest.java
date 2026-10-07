package com.flowops.modules.governance.scope;

import com.flowops.common.context.ScopeContext;
import com.flowops.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 越权语义单测（docs/09 M2 DoD：「区分不存在/越权」）。
 *
 * <p>关键点在于：行级过滤让"越权"和"不存在"在查询结果上完全一样，
 * 只有做一次无过滤的存在性探测才能区分 —— 本测试就是守住这个区分。</p>
 */
class ScopeGuardTest {

    private final ScopeGuard guard = new ScopeGuard();

    @AfterEach
    void tearDown() {
        ScopeContext.clear();
    }

    @Test
    void 存在但不可见_40301_并附带scope与资源标识() {
        ScopeContext ctx = new ScopeContext();
        ctx.setType(ScopeContext.Type.AUTHORIZED_CLUSTER);
        ScopeContext.set(ctx);

        assertThatThrownBy(() -> {
            throw guard.notVisible("CLUSTER", "CL-20261007-0001", () -> true);
        })
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getErrorCode().getCode()).isEqualTo(40301);
                    assertThat(String.valueOf(biz.getPayload())).contains("AUTHORIZED_CLUSTER")
                            .contains("CL-20261007-0001");
                });
    }

    @Test
    void 真不存在_40400() {
        assertThatThrownBy(() -> {
            throw guard.notVisible("CLUSTER", "CL-9999", () -> false);
        })
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(40400);
    }

    @Test
    void 探测期间临时关闭过滤_且结束后恢复原上下文() {
        ScopeContext original = new ScopeContext();
        original.setType(ScopeContext.Type.PROJECT);
        ScopeContext.set(original);

        ScopeContext[] seenInsideProbe = new ScopeContext[1];
        guard.notVisible("CLUSTER", "CL-1", () -> {
            seenInsideProbe[0] = ScopeContext.get();   // 探测时必须是"无上下文"（不注入任何条件）
            return false;
        });

        assertThat(seenInsideProbe[0]).isNull();
        assertThat(ScopeContext.get()).isSameAs(original);   // 临界区结束必须原样恢复
    }
}
