package com.flowops.domain.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Java String ⇄ PostgreSQL jsonb 的类型处理器（docs/05 §1.2：快照类字段用 jsonb）。
 *
 * <p><b>为什么需要它</b>：PG 的 jsonb 列不接受 varchar 参数——实体里以 String 承载 JSON
 * （反序列化时机由调用方决定），INSERT/UPDATE 时必须显式以 {@code jsonb} 类型绑定，
 * 否则驱动报 "column is of type jsonb but expression is of type character varying"。</p>
 *
 * <p><b>用法</b>：实体字段 {@code @TableField(typeHandler = JsonbTypeHandler.class)}，
 * 且 {@code @TableName(autoResultMap = true)}（读取侧的 ResultMap 才会带上 TypeHandler）。</p>
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(parameter);
        ps.setObject(i, jsonb);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
