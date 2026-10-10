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
import java.time.LocalDate;
import java.util.Arrays;

/**
 * Java {@code LocalDate[]} ⇄ PostgreSQL {@code date[]} 的类型处理器（docs/05 §3.5
 * backfill_batch.skipped_dates）。
 *
 * <p>与 {@link StringArrayTypeHandler} 同理：PG 的 {@code date[]} 不接受 JDBC 默认绑定，
 * 必须 {@code createArrayOf("date", ...)}。空数组（而非 null）语义：DDL 默认 {@code '{}'}。</p>
 */
@MappedTypes(LocalDate[].class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class DateArrayTypeHandler extends BaseTypeHandler<LocalDate[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDate[] parameter, JdbcType jdbcType)
            throws SQLException {
        Array array = ps.getConnection().createArrayOf("date", parameter);
        ps.setArray(i, array);
    }

    @Override
    public LocalDate[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return read(rs.getArray(columnName));
    }

    @Override
    public LocalDate[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return read(rs.getArray(columnIndex));
    }

    @Override
    public LocalDate[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return read(cs.getArray(columnIndex));
    }

    private LocalDate[] read(Array array) throws SQLException {
        if (array == null) {
            return new LocalDate[0];
        }
        Object raw = array.getArray();
        return raw instanceof LocalDate[] values ? values : Arrays.stream((Object[]) raw)
                .map(v -> (LocalDate) v).toArray(LocalDate[]::new);
    }
}
