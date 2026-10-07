package com.flowops.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登记表 ↔ 真实 DDL 的一致性测试。
 *
 * <p><b>为什么需要这条测试</b>：{@link FlowopsDataPermissionHandler} 往 SQL 里注入的是
 * <b>裸列名</b>（{@code workflow_version.project_id = 3}）。列名写错时编译器、单元测试
 * （断言字符串拼接）都发现不了 —— 只有真的打库才炸，而「打库的那条路径」恰好是
 * <i>非管理员</i> 才会走到的（范围 ALL 不注入），联调时很容易漏。</p>
 *
 * <p>M3 就真的踩过一次：{@code workflow_version} 被登记到 {@code project_id}，
 * 而 docs/05 §3.4 的 DDL 里这张表<b>根本没有这一列</b> —— 于是任何版本查询
 * 在 PROJECT 范围下都会以 {@code column project_id does not exist} 报 500。
 * 这条测试把那一类错误提前到构建期。</p>
 *
 * <p>做法：把 {@code db/migration/*.sql} 全量拼起来，对每一条登记项取出
 * {@code CREATE TABLE <表> ( ... )} 的列定义区，断言语义列名确实作为<b>列定义</b>出现
 * （用行首锚定，避免把 {@code REFERENCES other(id)} 里的表名误当成列）。</p>
 */
class DataPermissionSchemaConsistencyTest {

    private static final String COLUMN_DEF_TEMPLATE = "^\\s*%s\\s+\\S";

    @Test
    void 项目维度登记表的列名必须真实存在于DDL() {
        assertColumnsExist(FlowopsDataPermissionHandler.projectScopedColumns(), "项目维度");
    }

    @Test
    void 集群维度登记表的列名必须真实存在于DDL() {
        assertColumnsExist(FlowopsDataPermissionHandler.clusterScopedColumns(), "集群维度");
    }

    /**
     * 反向断言：派生表（无 project_id 的版本/步骤/边）<b>不能</b>被登记。
     *
     * <p>这几张表的可见性走「先取自身行 → 回父对象判范围」的路径（与 operator_version 同理）。
     * 若有人"顺手"把它们加回登记表，DDL 一致性测试也会失败，但这条断言给出的是
     * 更直接的原因说明。</p>
     */
    @Test
    void 无项目归属列的派生表不得进入登记表() {
        Set<String> registered = FlowopsDataPermissionHandler.projectScopedTables();
        assertThat(registered).doesNotContain("workflow_version", "workflow_step", "workflow_edge");
    }

    private void assertColumnsExist(Map<String, String> registry, String dimension) {
        String ddl = readAllMigrations();
        Map<String, String> missing = new LinkedHashMap<>();
        registry.forEach((table, column) -> {
            String block = createTableBlock(ddl, table);
            if (block == null) {
                missing.put(table, column + "（整张表在迁移脚本里都找不到）");
                return;
            }
            // 行首锚定：只认"列定义"，不会把 REFERENCES other(id) 里的表名当成列名
            Pattern columnDef = Pattern.compile(
                    String.format(COLUMN_DEF_TEMPLATE, Pattern.quote(column)), Pattern.MULTILINE);
            if (!columnDef.matcher(block).find()) {
                missing.put(table, column);
            }
        });
        // 一条断言报全部缺项：一次就能看到所有对不上的表，不必修一条跑一次
        assertThat(missing)
                .as("%s登记表与实际 DDL 不一致：列不存在 → 该端点在对应数据范围下必然 500", dimension)
                .isEmpty();
        assertThat(registry).as("%s登记表不应为空（空表意味着过滤被静默关掉）", dimension).isNotEmpty();
    }

    /** 取出 {@code CREATE TABLE x ( ... );} 的整段（本仓库 DDL 统一以行首 {@code );} 收尾）。 */
    private String createTableBlock(String ddl, String table) {
        int start = ddl.indexOf("CREATE TABLE " + table + " (");
        if (start < 0) {
            return null;
        }
        int end = ddl.indexOf("\n);", start);
        return end < 0 ? ddl.substring(start) : ddl.substring(start, end);
    }

    /** 拼起全部迁移脚本：建表在 V1，但后续版本可能 ALTER 补列，只看 V1 会误报。 */
    private String readAllMigrations() {
        List<String> files = migrationFiles();
        assertThat(files).as("没找到任何迁移脚本，测试本身失效（路径变了？）").isNotEmpty();
        StringBuilder sb = new StringBuilder();
        for (String name : files) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
                assertThat(in).as("迁移脚本读不到: %s", name).isNotNull();
                sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            } catch (IOException e) {
                throw new IllegalStateException("读取迁移脚本失败: " + name, e);
            }
        }
        return sb.toString();
    }

    private List<String> migrationFiles() {
        URL dir = getClass().getClassLoader().getResource("db/migration");
        List<String> names = new ArrayList<>();
        if (dir == null || !"file".equals(dir.getProtocol())) {
            return names;   // 打成 jar 时不列目录（本模块测试恒为目录，此处只是兜底）
        }
        try (Stream<Path> paths = Files.list(Path.of(dir.toURI()))) {
            paths.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .forEach(p -> names.add("db/migration/" + p.getFileName()));
        } catch (Exception e) {
            throw new IllegalStateException("列出迁移脚本目录失败", e);
        }
        return names;
    }
}
