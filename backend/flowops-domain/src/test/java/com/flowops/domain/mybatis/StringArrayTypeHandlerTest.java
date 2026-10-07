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
 * {@code String[]} ⇄ {@code text[]} 映射单测（{@code executor_node.tags}）。
 *
 * <p>tags 是调度期标签约束（PRD §12.1）的判定依据：读成 {@code "{gpu,ssd}"} 这种字符串，
 * 或读成 null，都会让标签匹配静默失效 —— 任务照跑，只是跑在了不该跑的节点上。</p>
 */
class StringArrayTypeHandlerTest {

    private final StringArrayTypeHandler handler = new StringArrayTypeHandler();

    private static Array pgArray(Object raw) throws Exception {
        Array array = mock(Array.class);
        when(array.getArray()).thenReturn(raw);
        return array;
    }

    @Test
    void 写参数_按text数组绑定() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        Array created = mock(Array.class);
        when(ps.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("text"), any())).thenReturn(created);

        handler.setNonNullParameter(ps, 1, new String[]{"gpu", "ssd"}, JdbcType.ARRAY);

        verify(connection).createArrayOf(eq("text"), eq(new String[]{"gpu", "ssd"}));
        verify(ps).setArray(1, created);
    }

    @Test
    void 读列名_已是String数组则原样返回() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        String[] raw = {"gpu", "ssd"};
        Array array = pgArray(raw);
        when(rs.getArray("tags")).thenReturn(array);

        assertThat(handler.getNullableResult(rs, "tags")).isSameAs(raw);
    }

    @Test
    void 读列索引_Object数组逐项转字符串() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        Array array = pgArray(new Object[]{"gpu", 8, "ssd"});
        when(rs.getArray(4)).thenReturn(array);

        assertThat(handler.getNullableResult(rs, 4)).containsExactly("gpu", "8", "ssd");
    }

    /** DDL 默认 {@code '{}'}：返回 null 会让前端与调度侧多出一层判空分支。 */
    @Test
    void 读存过过程出参_列为NULL返回空数组() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getArray(2)).thenReturn(null);

        assertThat(handler.getNullableResult(cs, 2)).isEmpty();
    }
}
