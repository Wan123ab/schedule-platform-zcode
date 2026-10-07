package com.flowops.common.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据范围上下文单测（D-19）。
 *
 * <p><b>为什么这个类必须有测试</b>：它决定"对哪些数据做"。写错的后果不是报错，而是
 * 行级过滤静默失效 —— 查询照常返回，只是返回了不该返回的行。其中
 * {@link ScopeContext#withoutScope} 的 <b>finally 恢复</b>是全部风险所在：一旦异常路径
 * 没恢复上下文，抛异常之后的整个请求都会在"无过滤"状态下查库。</p>
 */
class ScopeContextTest {

    @AfterEach
    void tearDown() {
        // ThreadLocal 必须在用例间清掉：Surefire 复用线程，残留上下文会让下一个用例
        // 在"别的用例的范围"里跑，而且失败现象会指向错误的用例
        ScopeContext.clear();
    }

    @Test
    void 默认无上下文() {
        assertThat(ScopeContext.get()).isNull();
    }

    @Test
    void 存取与清除() {
        ScopeContext ctx = new ScopeContext();
        ctx.setType(ScopeContext.Type.PROJECT);
        ctx.setVisibleProjectIds(Set.of(11L, 12L));

        ScopeContext.set(ctx);

        assertThat(ScopeContext.get()).isSameAs(ctx);
        assertThat(ScopeContext.get().getVisibleProjectIds()).containsExactlyInAnyOrder(11L, 12L);

        ScopeContext.clear();
        assertThat(ScopeContext.get()).isNull();
    }

    @Test
    void 新建上下文默认_NONE_且可见集为空() {
        ScopeContext ctx = new ScopeContext();

        assertThat(ctx.getType()).isEqualTo(ScopeContext.Type.NONE);
        assertThat(ctx.isAll()).isFalse();
        assertThat(ctx.getVisibleProjectIds()).isEmpty();
        assertThat(ctx.getVisibleClusterIds()).isEmpty();
    }

    @Test
    void ALL_范围判定() {
        ScopeContext ctx = new ScopeContext();
        ctx.setType(ScopeContext.Type.ALL);

        assertThat(ctx.isAll()).isTrue();
    }

    /** 探测临界区的正常路径：执行后必须回到原上下文，而不是留在"无过滤"。 */
    @Test
    void 无过滤临界区_执行后恢复原上下文() {
        ScopeContext original = new ScopeContext();
        original.setType(ScopeContext.Type.PROJECT);
        ScopeContext.set(original);

        Boolean exists = ScopeContext.withoutScope(() -> {
            assertThat(ScopeContext.get()).isNull();     // 临界区内确实关掉了过滤
            return Boolean.TRUE;
        });

        assertThat(exists).isTrue();
        assertThat(ScopeContext.get()).isSameAs(original);
    }

    /**
     * 异常路径也必须恢复 —— 这是本类最要紧的一条不变式。
     * 少了 finally，一次探测抛错就会让本请求剩余的所有查询失去行级过滤。
     */
    @Test
    void 无过滤临界区_抛异常也恢复原上下文() {
        ScopeContext original = new ScopeContext();
        original.setType(ScopeContext.Type.SELF_CREATED);
        ScopeContext.set(original);

        assertThatThrownBy(() -> ScopeContext.withoutScope(() -> {
            throw new IllegalStateException("探测查询失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ScopeContext.get()).isSameAs(original);
    }

    /** 原本就没有上下文时，临界区结束后要回到"仍然没有"，不能留下一个 null 占位。 */
    @Test
    void 无过滤临界区_原无上下文则执行后仍无上下文() {
        assertThat(ScopeContext.get()).isNull();

        ScopeContext.withoutScope(() -> null);

        assertThat(ScopeContext.get()).isNull();
    }

    /** 嵌套调用：内层恢复的是外层，不是最初值 —— 恢复的是"进入前的那一份"。 */
    @Test
    void 无过滤临界区_可嵌套且逐层恢复() {
        ScopeContext outer = new ScopeContext();
        outer.setType(ScopeContext.Type.PROJECT);
        ScopeContext.set(outer);

        ScopeContext.withoutScope(() -> {
            assertThat(ScopeContext.get()).isNull();
            ScopeContext.withoutScope(() -> {
                assertThat(ScopeContext.get()).isNull();
                return null;
            });
            assertThat(ScopeContext.get()).isNull();     // 内层退出后仍是"无过滤"
            return null;
        });

        assertThat(ScopeContext.get()).isSameAs(outer);
    }
}
