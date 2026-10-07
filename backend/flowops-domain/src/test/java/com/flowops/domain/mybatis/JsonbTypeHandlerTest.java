package com.flowops.domain.mybatis;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code String} ⇄ {@code jsonb} 映射单测（快照类字段）。
 *
 * <p><b>不显式绑 jsonb 会怎样</b>：驱动报
 * {@code column "env_vars" is of type jsonb but expression is of type character varying}。
 * 这条报错发生在写入时、且指向 SQL 类型而非业务字段，靠它反查成本很高 ——
 * 所以把"绑定值必须是 PGobject(jsonb)"直接钉进单测。</p>
 *
 * <p>读侧保持 String 而非反序列化成对象：反序列化时机交给 Service（实体不依赖 JSON 库）。</p>
 */
class JsonbTypeHandlerTest {

    private final JsonbTypeHandler handler = new JsonbTypeHandler();

    @Test
    void 写参数_以jsonb类型而非varchar绑定() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 2, "{\"cpu\":2,\"memory\":4096}", JdbcType.OTHER);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ps).setObject(eq(2), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(PGobject.class);
        PGobject bound = (PGobject) captor.getValue();
        assertThat(bound.getType()).isEqualTo("jsonb");
        assertThat(bound.getValue()).isEqualTo("{\"cpu\":2,\"memory\":4096}");
    }

    @Test
    void 读列名_原样返回JSON字符串() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("env_vars")).thenReturn("[{\"key\":\"MODE\"}]");

        assertThat(handler.getNullableResult(rs, "env_vars")).isEqualTo("[{\"key\":\"MODE\"}]");
    }

    @Test
    void 读列索引_原样返回JSON字符串() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(5)).thenReturn("{}");

        assertThat(handler.getNullableResult(rs, 5)).isEqualTo("{}");
    }

    @Test
    void 读存过过程出参_原样返回JSON字符串() throws Exception {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(1)).thenReturn("[]");

        assertThat(handler.getNullableResult(cs, 1)).isEqualTo("[]");
    }
}
