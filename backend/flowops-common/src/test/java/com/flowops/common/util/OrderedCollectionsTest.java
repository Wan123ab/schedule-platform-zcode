package com.flowops.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 保序不可变集合单测。
 *
 * <p>这个类的价值全在"顺序可预期"，所以测试的重心也是顺序：每条断言都写死完整顺序，
 * 而不是"包含这些元素"。特别地，{@code 保序Map_八个元素_顺序与插入一致} 与
 * {@code 保序Set_八个元素_顺序与插入一致} 是<b>守卫用例</b> —— 一旦有人把实现换回
 * {@link Map#copyOf} / {@link Set#copyOf}，JDK 那个随机 SALT 会让这两条在<b>多数</b>
 * JVM 上立刻变红（不是每条 JVM 都红，因为随机也可能碰巧对上，这正是原缺陷能潜伏的原因）。</p>
 */
class OrderedCollectionsTest {

    /** 八个键：元素够多，JDK 不可变容器的随机打散才藏不住。 */
    private static LinkedHashMap<String, Object> eightEntries() {
        LinkedHashMap<String, Object> src = new LinkedHashMap<>();
        src.put("input_path", "/data/in");
        src.put("partitions", 8);
        src.put("engine", "spark");
        src.put("dry_run", true);
        src.put("queue", "root.etl");
        src.put("priority", 3);
        src.put("owner", "wan");
        src.put("retry", 2);
        return src;
    }

    // ── Map ─────────────────────────────────────────────────

    @Test
    void 保序Map_迭代顺序等于插入顺序() {
        LinkedHashMap<String, Object> src = new LinkedHashMap<>();
        src.put("b", 2);
        src.put("a", 1);
        src.put("c", 3);

        assertThat(OrderedCollections.orderedMap(src).keySet()).containsExactly("b", "a", "c");
    }

    @Test
    void 保序Map_八个元素_顺序与插入一致() {
        assertThat(OrderedCollections.orderedMap(eightEntries()).keySet())
                .containsExactly("input_path", "partitions", "engine", "dry_run",
                        "queue", "priority", "owner", "retry");
    }

    @Test
    void 保序Map_不可写_put被拒() {
        Map<String, Object> immutable = OrderedCollections.orderedMap(eightEntries());

        assertThatThrownBy(() -> immutable.put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 保序Map_不可写_迭代器改值也被拒() {
        Map<String, Object> immutable = OrderedCollections.orderedMap(eightEntries());
        Map.Entry<String, Object> first = immutable.entrySet().iterator().next();

        assertThatThrownBy(() -> first.setValue("tampered"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 保序Map_键为null立即失败() {
        LinkedHashMap<String, Object> src = new LinkedHashMap<>();
        src.put("ok", 1);
        src.put(null, 2);

        assertThatThrownBy(() -> OrderedCollections.orderedMap(src))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("键");
    }

    @Test
    void 保序Map_值为null立即失败() {
        LinkedHashMap<String, Object> src = new LinkedHashMap<>();
        src.put("ok", 1);
        src.put("bad", null);

        assertThatThrownBy(() -> OrderedCollections.orderedMap(src))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("值");
    }

    @Test
    void 保序Map_空输入返回空且可迭代() {
        Map<String, Object> empty = OrderedCollections.orderedMap(new LinkedHashMap<>());

        assertThat(empty).isEmpty();
        assertThat(empty.keySet()).isEmpty();
    }

    @Test
    void 保序Map_不破坏等值语义() {
        LinkedHashMap<String, Object> src = eightEntries();

        assertThat(OrderedCollections.orderedMap(src)).isEqualTo(src);
        assertThat(OrderedCollections.orderedMap(src)).hasSameHashCodeAs(src);
    }

    // ── Set ─────────────────────────────────────────────────

    @Test
    void 保序Set_迭代顺序等于插入顺序() {
        Set<String> src = new LinkedHashSet<>(List.of("b", "a", "c"));

        assertThat(OrderedCollections.orderedSet(src)).containsExactly("b", "a", "c");
    }

    @Test
    void 保序Set_八个元素_顺序与插入一致() {
        assertThat(OrderedCollections.orderedSet(new LinkedHashSet<>(List.of(
                "input_path", "partitions", "engine", "dry_run",
                "queue", "priority", "owner", "retry"))))
                .containsExactly("input_path", "partitions", "engine", "dry_run",
                        "queue", "priority", "owner", "retry");
    }

    @Test
    void 保序Set_哈希集输入也照其当时顺序落位() {
        // HashSet 本身无序，这里只要求"原样搬运"，不要求排好
        Set<Integer> src = new HashSet<>(List.of(11, 22, 33));

        assertThat(OrderedCollections.orderedSet(src))
                .containsExactlyElementsOf(src);
    }

    @Test
    void 保序Set_不可写_add被拒() {
        Set<String> immutable = OrderedCollections.orderedSet(new LinkedHashSet<>(List.of("a", "b")));

        assertThatThrownBy(() -> immutable.add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 保序Set_元素为null立即失败() {
        Set<String> src = new LinkedHashSet<>();
        src.add("ok");
        src.add(null);

        assertThatThrownBy(() -> OrderedCollections.orderedSet(src))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 保序Set_空输入返回空() {
        assertThat(OrderedCollections.orderedSet(List.of())).isEmpty();
    }

    @Test
    void 保序Set_可直接接Map的键集() {
        // 本次修复最典型的调用形态：把内部 Map 的 keySet 交给外部
        assertThat(OrderedCollections.orderedSet(eightEntries().keySet()))
                .containsExactly("input_path", "partitions", "engine", "dry_run",
                        "queue", "priority", "owner", "retry");
    }

    @Test
    void 保序Set_遍历时元素不重复产出() {
        AtomicInteger seen = new AtomicInteger();
        OrderedCollections.orderedSet(new LinkedHashSet<>(List.of("a", "b", "c")))
                .forEach(e -> seen.incrementAndGet());

        assertThat(seen.get()).isEqualTo(3);
    }
}
