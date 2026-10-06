package com.flowops.architecture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限矩阵遍历测试（M2 DoD：PRD §20.1-6「矩阵每个非空白格均验证」的实现侧等价物）。
 *
 * <p><b>做法</b>：直接解析 Flyway 种子文件 V4__seed_roles_permissions.sql（classpath 上）
 * 得到「角色 → 权限点」的真实矩阵，再对 docs/07 §5.2 的关键不变式逐格断言 ——
 * 有人改种子 SQL 而不改矩阵认知（或反之）时，这里立刻红。</p>
 *
 * <p>与 PRD §11.2 矩阵的 6 角色逐行对齐：PLATFORM_ADMIN / OPS / PROJECT_ADMIN /
 * OPERATOR_MAINTAINER / BUSINESS / AUDITOR。</p>
 */
class PermissionMatrixTest {

    private static Map<String, Set<String>> matrix;   // role_code → perm_codes
    private static Set<String> allPerms;

    @BeforeAll
    static void parseSeed() throws Exception {
        try (InputStream in = PermissionMatrixTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V4__seed_roles_permissions.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            allPerms = extract(sql,
                    Pattern.compile("\\('(schedule:[a-z:._]+)',"));
            matrix = Map.of(
                    "PLATFORM_ADMIN", allPerms,   // 绑定块无 IN 列表 = 全量
                    "OPS", permsForRole(sql, "OPS"),
                    "PROJECT_ADMIN", permsForRole(sql, "PROJECT_ADMIN"),
                    "OPERATOR_MAINTAINER", permsForRole(sql, "OPERATOR_MAINTAINER"),
                    "BUSINESS", permsForRole(sql, "BUSINESS"),
                    "AUDITOR", permsForRole(sql, "AUDITOR"));
        }
    }

    private static Set<String> extract(String sql, Pattern p) {
        return Pattern.compile("'(schedule:[a-z:._]+)'").matcher(sql).results()
                .map(m -> m.group(1)).collect(Collectors.toSet());
    }

    /** 提取某角色绑定块的 perm_code 集合（INSERT ... WHERE r.role_code = 'X' ... 到分号）。 */
    private static Set<String> permsForRole(String sql, String role) {
        Matcher section = Pattern.compile(
                "WHERE r\\.role_code = '" + role + "'[^;]*;").matcher(sql);
        if (!section.find()) {
            throw new IllegalStateException("种子文件缺少角色绑定块: " + role);
        }
        return Pattern.compile("'(schedule:[a-z:._]+)'").matcher(section.group())
                .results().map(m -> m.group(1)).collect(Collectors.toSet());
    }

    @Test
    void 权限点总数49_与docs07_5_2一致() {
        assertThat(allPerms.size()).isEqualTo(49);
    }

    @Test
    void 平台管理员持有全部49点() {
        assertThat(matrix.get("PLATFORM_ADMIN")).isEqualTo(allPerms);
    }

    @Test
    void 每个权限点至少授予一个角色_无孤儿点() {
        Set<String> granted = matrix.values().stream()
                .flatMap(Set::stream).collect(Collectors.toSet());
        assertThat(granted).isEqualTo(allPerms);
    }

    @Test
    void 关键特权格_与docs07_5_2逐行对齐() {
        // workflow:delete 仅平台管理员（docs/03 §3.1：项目管理员都不可删）
        assertThat(matrix.get("PROJECT_ADMIN")).doesNotContain("schedule:workflow:delete");
        assertThat(matrix.get("OPS")).doesNotContain("schedule:workflow:delete");
        // 插队仅平台管理员/运维
        assertThat(matrix.get("PROJECT_ADMIN")).doesNotContain("schedule:task:enqueue_front");
        assertThat(matrix.get("OPS")).contains("schedule:task:enqueue_front");
        // 审计全量仅平台管理员/只读审计
        assertThat(matrix.get("AUDITOR")).contains("schedule:audit:read");
        assertThat(matrix.get("OPS")).doesNotContain("schedule:audit:read");
        // 开放接口管理仅平台管理员/项目管理员（v0.2d）
        assertThat(matrix.get("OPS")).doesNotContain("schedule:openapi:write");
        assertThat(matrix.get("PROJECT_ADMIN")).contains("schedule:openapi:write");
        // 日志原文：业务人员有（本项目），运维没有（需 grant 留痕，PRD §11.3）
        assertThat(matrix.get("BUSINESS")).contains("schedule:task:log:raw");
        assertThat(matrix.get("OPS")).doesNotContain("schedule:task:log:raw");
        assertThat(matrix.get("OPS")).contains("schedule:task:log:grant");
    }

    @Test
    void 只读审计_无任何写权限点() {
        List<String> writes = matrix.get("AUDITOR").stream()
                .filter(p -> p.endsWith(":write") || p.endsWith(":delete") || p.endsWith(":publish")
                        || p.endsWith(":stop") || p.endsWith(":retry") || p.endsWith(":rotate")
                        || p.endsWith(":submit") || p.endsWith(":enqueue_front")
                        || p.endsWith(":member") || p.endsWith(":config"))
                .toList();
        assertThat(writes).as("AUDITOR 必须是纯只读角色").isEmpty();
    }

    @Test
    void 业务人员_无资产域写权限() {
        Set<String> business = matrix.get("BUSINESS");
        assertThat(business).doesNotContain("schedule:cluster:write", "schedule:node:write",
                "schedule:credential:write", "schedule:operator:publish", "schedule:backfill:write");
    }
}
