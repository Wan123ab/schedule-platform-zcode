package com.flowops.common.util;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 保序的不可变集合副本 —— 需要"不可变 + 顺序可预期"时代替 {@link Map#copyOf} / {@link Set#copyOf}。
 *
 * <p><b>为什么不能直接用 JDK 的 {@code copyOf}</b>：JDK 的不可变容器实现
 * （{@code java.util.ImmutableCollections}）会用一个<b>每次 JVM 启动都重新随机</b>的
 * 内部 SALT 去打散桶的摆放，因此它的迭代顺序是"未指定"的。同一份输入在两次运行里
 * 可能给出两种顺序 —— 实测同一条命令连跑 6 次，有 2 次与另外 4 次相反。</p>
 *
 * <p>后果不只是"看起来不一样"：</p>
 * <ul>
 *   <li>{@code equals}/{@code hashCode} 不看顺序，故<b>单元测试可能今天绿、明天红</b>
 *       （本类诞生的直接原因：一条断言"按模板 seq 顺序展开"的用例，
 *       在 {@code mvn test} 的 JVM 里绿、在 {@code clean verify} 的新 JVM 里红）；</li>
 *   <li>快照落库（{@code task.variable_snapshot}）的 JSON 键序在两次运行间漂移，
 *       diff 读不出真实变更；</li>
 *   <li>命令回显与参数面板的行序与用户上传时配置的顺序对不上。</li>
 * </ul>
 *
 * <p><b>该用哪一档</b>：结果只用于 {@code get}/{@code contains} 查询 → 照用
 * {@code copyOf}（如状态转移表、权限集合，顺序对外不可观测）；结果会被<b>遍历</b>
 * ——渲染、落库、拼诊断消息、出网给前端——→ 用本类。</p>
 *
 * <p>失败姿态与 {@code copyOf} 一致：键/值为 {@code null} 立即抛 NPE（不静默收下），
 * 唯一变化是迭代顺序从"随机"变成"插入顺序"。</p>
 */
public final class OrderedCollections {

    private OrderedCollections() {
    }

    /**
     * 保序不可变副本：迭代顺序 = {@code source} 的迭代顺序。
     *
     * <p>用 {@link LinkedHashMap} 承载顺序，再套 {@code unmodifiableMap}
     * 断掉写入 —— 注意<b>不能</b>写成"先 {@code copyOf} 再包一层"，
     * 那样顺序已经在 {@code copyOf} 里丢掉了。</p>
     */
    public static <K, V> Map<K, V> orderedMap(Map<K, V> source) {
        LinkedHashMap<K, V> copy = new LinkedHashMap<>(source);
        copy.forEach((k, v) -> {
            Objects.requireNonNull(k, "orderedMap 的键不可为 null");
            Objects.requireNonNull(v, "orderedMap 的值不可为 null");
        });
        return Collections.unmodifiableMap(copy);
    }

    /** 保序不可变副本：迭代顺序 = {@code source} 的迭代顺序。 */
    public static <E> Set<E> orderedSet(Collection<E> source) {
        LinkedHashSet<E> copy = new LinkedHashSet<>(source);
        copy.forEach(e -> Objects.requireNonNull(e, "orderedSet 的元素不可为 null"));
        return Collections.unmodifiableSet(copy);
    }
}
