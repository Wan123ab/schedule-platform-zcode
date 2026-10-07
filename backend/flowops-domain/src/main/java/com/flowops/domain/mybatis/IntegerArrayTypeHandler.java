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

/**
 * Java {@code Integer[]} ⇄ PostgreSQL {@code integer[]} 的类型处理器。
 *
 * <p><b>为什么需要它</b>：算子版本的 {@code success_codes} 是 {@code integer[]}
 * （docs/05 §3.3：成功退出码，PRD §12.4-5）。PG 不接受把 Java 数组直接当 varchar 绑定，
 * 必须显式 {@code createArrayOf("int4", ...)}；读取侧也要从 {@link PgArray} 还原成
 * {@code Integer[]}，否则会拿到 {@code PgArray.toString()} 这种 {@code "{0,1}"} 字符串。</p>
 *
 * <p><b>为什么不用 {@code String[]} 存</b>：退出码是数值语义，用字符串承载会让
 * scheduler 侧每次判断都要 {@code Integer.parseInt}，把"格式错误"从数据层推迟到运行期。</p>
 *
 * <p><b>空值纪律（读侧）</b>：读到的 SQL NULL 一律还原成<b>空数组</b>而非 null
 * （见 {@link #toIntegers}），调用方不必到处判空。</p>
 *
 * <p><b>空值纪律（写侧）</b>：该列 DDL 是 {@code NOT NULL DEFAULT '{0}'}，写成 SQL NULL
 * 会直接违反约束。MyBatis-Plus 的字段策略是 NOT_NULL —— 字段为 null 时整列不进 SQL，
 * 由 DB 默认值兜底，故空值不会真的落到本处理器上。换句话说：<b>要表达"空"就传
 * {@code new Integer[0]}，不要传 null</b>，让意图显式落在数据里。</p>
 */
@MappedTypes(Integer[].class)
@MappedJdbcTypes(JdbcType.ARRAY)
public class IntegerArrayTypeHandler extends BaseTypeHandler<Integer[]> {

    private static final Integer[] EMPTY = new Integer[0];

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Integer[] parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setArray(i, ps.getConnection().createArrayOf("int4", parameter));
    }

    @Override
    public Integer[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toIntegers(rs.getArray(columnName));
    }

    @Override
    public Integer[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toIntegers(rs.getArray(columnIndex));
    }

    @Override
    public Integer[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toIntegers(cs.getArray(columnIndex));
    }

    /** 与 {@link StringArrayTypeHandler} 同理：null 返回空数组而非 null，调用方不必到处判空。 */
    private Integer[] toIntegers(Array array) throws SQLException {
        if (array == null) {
            return EMPTY;
        }
        Object raw = array.getArray();
        if (raw instanceof Integer[] ints) {
            return ints;
        }
        // 驱动可能给 int[] 或 Object[]，统一收敛成 Integer[]
        if (raw instanceof int[] primitive) {
            Integer[] boxed = new Integer[primitive.length];
            for (int i = 0; i < primitive.length; i++) {
                boxed[i] = primitive[i];
            }
            return boxed;
        }
        Object[] objects = (Object[]) raw;
        Integer[] result = new Integer[objects.length];
        for (int i = 0; i < objects.length; i++) {
            result[i] = objects[i] == null ? null : ((Number) objects[i]).intValue();
        }
        return result;
    }
}
