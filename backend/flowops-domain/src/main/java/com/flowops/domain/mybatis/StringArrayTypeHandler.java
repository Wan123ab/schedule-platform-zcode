package com.flowops.domain.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Java {@code String[]} ⇄ PostgreSQL {@code text[]} 的类型处理器（docs/05 §3.3 executor_node.tags）。
 *
 * <p><b>为什么需要它</b>：PG 的 {@code text[]} 不接受 JDBC 默认的 {@code setString} 绑定；
 * 必须用 {@code createArrayOf("text", ...)} 才不会被判为「表达式类型不匹配」。
 * 标签（tags）是调度期标签约束（PRD §12.1）的判定依据，故单独建模而非塞进 jsonb。</p>
 *
 * <p><b>用法</b>：实体字段 {@code @TableField(typeHandler = StringArrayTypeHandler.class)}
 * 且 {@code @TableName(autoResultMap = true)}（读侧 ResultMap 才会带上本处理器）。</p>
 */
@MappedTypes(String[].class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class StringArrayTypeHandler extends BaseTypeHandler<String[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType)
            throws SQLException {
        Array array = ps.getConnection().createArrayOf("text", parameter);
        ps.setArray(i, array);
    }

    @Override
    public String[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return read(rs.getArray(columnName));
    }

    @Override
    public String[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return read(rs.getArray(columnIndex));
    }

    @Override
    public String[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return read(cs.getArray(columnIndex));
    }

    /** 空数组（而非 null）语义：DDL 默认 {@code '{}'}，返回 null 会让前端判空分支复杂化。 */
    private String[] read(Array array) throws SQLException {
        if (array == null) {
            return new String[0];
        }
        Object raw = array.getArray();
        return raw instanceof String[] values ? values : Arrays.stream((Object[]) raw)
                .map(String::valueOf).toArray(String[]::new);
    }
}
