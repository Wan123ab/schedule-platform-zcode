package com.flowops.architecture;

import com.flowops.common.enums.ScopeType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色数据范围口径守护（M2 新增）。
 *
 * <p><b>为什么必须有这条测试</b>：DataScope 的枚举值分布在两处 —— Java 的 {@link ScopeType}
 * 与数据库的 {@code role.scope_type}。两边一旦漂移，{@code DataScopeResolver} 就会把它
 * 解析成 {@code NONE}，而 <b>NONE 的语义是"不过滤"</b>：不是"看不到"，而是"全看得见"。
 * 这类失败没有任何报错、没有异常、单测也不会红 —— 只有把"种子数据"也纳入断言才拦得住。</p>
 *
 * <p><b>做法</b>：解析 V4 种子里的 role 行 → 依次套用所有迁移脚本中的 scope_type 改名规则
 * → 断言最终值全部是合法的 {@link ScopeType} 代码，且不残留历史命名。</p>
 */
class RoleScopeTypeTest {

    private static final Pattern ROLE_ROW = Pattern.compile(
            "\\('([A-Z_]+)',\\s*'[^']*',\\s*'([A-Z_]+)'");

    /** 迁移脚本按版本号升序执行，故这里也要排序（V6 之后可能还有 V7…）。 */
    private static List<String> migrations() {
        return List.of("V4__seed_roles_permissions.sql", "V6__fix_role_scope_type.sql");
    }

    private static String read(String resource) {
        try (InputStream in = RoleScopeTypeTest.class.getClassLoader().getResourceAsStream("db/migration/" + resource)) {
            assertThat(in).as("缺少迁移脚本: " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 角色 → 最终 scope_type（V4 初值 + V6 的改名规则）。 */
    private static Map<String, String> finalRoleScopes() {
        Map<String, String> scopes = new LinkedHashMap<>();
        Matcher rows = ROLE_ROW.matcher(read("V4__seed_roles_permissions.sql"));
        while (rows.find()) {
            scopes.put(rows.group(1), rows.group(2));
        }
        String v6 = read("V6__fix_role_scope_type.sql");
        // 按 value 改名：UPDATE role SET scope_type = '新' WHERE scope_type = '旧'
        Matcher byValue = Pattern.compile(
                "UPDATE role SET scope_type = '([A-Z_]+)'\\s+WHERE scope_type = '([A-Z_]+)'").matcher(v6);
        Map<String, String> remap = new LinkedHashMap<>();
        while (byValue.find()) {
            remap.put(byValue.group(2), byValue.group(1));
        }
        scopes.replaceAll((role, scope) -> remap.getOrDefault(scope, scope));
        // 按角色改名：UPDATE role SET scope_type = '新' WHERE role_code = 'X'
        Matcher byRole = Pattern.compile(
                "UPDATE role SET scope_type = '([A-Z_]+)'\\s+WHERE role_code = '([A-Z_]+)'").matcher(v6);
        while (byRole.find()) {
            scopes.put(byRole.group(2), byRole.group(1));
        }
        return scopes;
    }

    @Test
    void 六个角色的scope_type_全部是合法ScopeType代码() {
        var valid = Arrays.stream(ScopeType.values()).map(ScopeType::getCode).collect(Collectors.toSet());

        Map<String, String> scopes = finalRoleScopes();

        assertThat(scopes).hasSize(6);   // 6 个内置角色（docs/03 §3.1）
        assertThat(scopes).allSatisfy((role, scope) ->
                assertThat(valid).as("角色 %s 的 scope_type=%s 不是合法 ScopeType", role, scope).contains(scope));
    }

    @Test
    void 不残留历史命名_CLUSTER_SELF_TENANT() {
        List<String> stale = new ArrayList<>();
        finalRoleScopes().forEach((role, scope) -> {
            if (List.of("CLUSTER", "SELF", "TENANT").contains(scope)) {
                stale.add(role + "=" + scope);
            }
        });

        assertThat(stale).as("残留历史命名会让该角色解析成 NONE（= 不过滤，静默越权）").isEmpty();
    }

    @Test
    void 运维角色必须是AUTHORIZED_CLUSTER_业务与算子维护者必须是SELF_CREATED() {
        Map<String, String> scopes = finalRoleScopes();

        // 逐条对齐 docs/03 §3.1 的角色说明
        assertThat(scopes.get("OPS")).isEqualTo(ScopeType.AUTHORIZED_CLUSTER.getCode());
        assertThat(scopes.get("BUSINESS")).isEqualTo(ScopeType.SELF_CREATED.getCode());
        assertThat(scopes.get("OPERATOR_MAINTAINER")).isEqualTo(ScopeType.SELF_CREATED.getCode());
        assertThat(scopes.get("PLATFORM_ADMIN")).isEqualTo(ScopeType.ALL.getCode());
        assertThat(scopes.get("AUDITOR")).isEqualTo(ScopeType.ALL.getCode());
        assertThat(scopes.get("PROJECT_ADMIN")).isEqualTo(ScopeType.PROJECT.getCode());
    }

    @Test
    void 迁移脚本自带收敛自检_防止漏改分支() {
        // V6 末尾的 DO $$ ... RAISE EXCEPTION 是"迁移后仍有旧命名就整体失败"的兜底，
        // 这条断言保证那段自检不会被后人顺手删掉
        assertThat(read("V6__fix_role_scope_type.sql")).contains("RAISE EXCEPTION");
    }
}
