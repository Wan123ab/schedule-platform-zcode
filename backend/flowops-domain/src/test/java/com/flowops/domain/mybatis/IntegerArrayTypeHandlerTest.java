package com.flowops.domain.mybatis;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code Integer[]} ⇄ {@code integer[]} 映射单测。
 *
 * <p><b>为什么这类处理器必须有测试</b>：type handler 是"静默错映射"的典型位置 ——
 * 映射错了不会抛异常，只会让 {@code success_codes} 悄悄变成 {@code "{0,1}"} 这样的字符串
 * 或全部为 null，直到调度器判错成败时才暴露，而那时现场已经离成因很远。</p>
 *
 * <p><b>驱动给什么形状是未知的</b>：PG 驱动对 {@code int4[]} 可能给 {@code int[]}、
 * {@code Integer[]} 或 {@code Object[]}（视版本与取值路径而定），三条分支都要钉住 ——
 * 只测一条就等于假设了驱动的实现细节。</p>
 */
class IntegerArrayTypeHandlerTest {

    private final IntegerArrayTypeHandler handler = new IntegerArrayTypeHandler();

    private static Array pgArray(Object raw) throws Exception {
        Array array = mock(Array.class);
        when(array.getArray()).thenReturn(raw);
        return array;
    }

    // ── 写侧 ────────────────────────────────────────────────

    /** 必须显式 createArrayOf("int4")：PG 不接受把 Java 数组当 varchar 绑定。 */
    @Test
    void 写参数_按int4数组绑定() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        Array created = mock(Array.class);
        when(ps.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("int4"), any())).thenReturn(created);

        handler.setNonNullParameter(ps, 3, new Integer[]{0, 1, 137}, JdbcType.ARRAY);

        verify(connection).createArrayOf(eq("int4"), eq(new Integer[]{0, 1, 137}));
        verify(ps).setArray(3, created);
    }

    // ── 读侧：三条驱动形状分支 ────────────────────────────────

    @Test
    void 读列名_原始int数组装箱为Integer数组() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        Array array = pgArray(new int[]{0, 1});
        when(rs.getArray("success_codes")).thenReturn(array);

        assertThat(handler.getNullableResult(rs, "success_codes")).containsExactly(0, 1);
    }

    @Test
    void 读列索引_已是Integer数组则原样返回() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        Integer[] raw = {137, 255};
        Array array = pgArray(raw);
        when(rs.getArray(2)).thenReturn(array);

        assertThat(handler.getNullableResult(rs, 2)).isSameAs(raw);
    }

    /** 驱动给 Object[] 时逐个取 intValue；数组里的 null 要保留为 null（不能变成 0）。 */
    @Test
    void 读存过过程出参_Object数组收敛且保留null元素() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        Array array = pgArray(new Object[]{1, null, 3});
        when(cs.getArray(1)).thenReturn(array);

        assertThat(handler.getNullableResult(cs, 1)).containsExactly(1, null, 3);
    }

    /**
     * 列为 NULL 时返回<b>空数组</b>而非 null：调用方（调度器判成败）不必到处判空，
     * 而"空成功码集合"本身就是合法语义（有算子靠退出码以外的信号判成败）。
     */
    @Test
    void 读列为NULL_返回空数组而非null() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getArray("success_codes")).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "success_codes")).isEmpty();
    }
}
