package com.flowops.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper XML 里的限定列名（{@code 别名.列}）↔ 真实 DDL 的一致性测试。
 *
 * <p><b>为什么需要这条测试（O-21 的正面收口）</b>：本仓库的 SQL 一律写在 XML，
 * 其中有若干语句<b>跨域</b>引用别的模块的表 —— 典型是
 * {@code OperatorMapper.countVersionReferences} 与 {@code findReferences}：
 * 它们 join 了 {@code workflow_step} / {@code workflow_version} / {@code workflow}。
 * 而这两条语句在单测里是<b>被 mock 掉的</b>（返回一个数字就行），于是：</p>
 * <ul>
 *   <li>改 {@code workflow_version} 的列名 → 编译器不知道、单测不知道；</li>
 *   <li>只有"真的打库"才会报 {@code column xxx does not exist}，而那条路径是
 *       <b>删除算子</b>（低频、且做的人未必是改列名的人）。</li>
 * </ul>
 * <p>这条测试把"改列名"与"改 XML"绑成同一件事：不一致 → 构建期失败。
 * 不需要 PG，不需要 {@code @SpringBootTest}，因此可以随每次 {@code mvn test} 跑。</p>
 *
 * <p><b>做法</b>：① 从 {@code db/migration/*.sql} 里解析出「表 → 列集合」
 * （{@code CREATE TABLE} 块 + {@code ALTER TABLE ... ADD COLUMN}）；
 * ② 从每个 Mapper XML 的每条语句里解析出「表别名 → 表名」，再抓出所有
 * {@code 别名.列}；③ 逐个比对。</p>
 *
 * <p><b>刻意只查"别名限定的列"</b>：不带别名的列名（{@code UPDATE operator SET deleted=...}）
 * 风险低得多（改列名时那段 SQL 就在眼前），而把它们也纳入判定需要区分
 * {@code SET} 左值与右值，误报成本高于收益。</p>
 */
class MapperXmlSchemaConsistencyTest {

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s*\\(");
    private static final Pattern ALTER_ADD_COLUMN =
            Pattern.compile("(?i)ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)");
    private static final Pattern STATEMENT =
            Pattern.compile("(?is)<(select|insert|update|delete)\\b[^>]*>(.*?)</\\1>");
    private static final Pattern TABLE_REF =
            Pattern.compile("(?i)\\b(from|join)\\s+([a-z_][a-z0-9_]*)(?:\\s+(?:as\\s+)?([a-z_][a-z0-9_]*))?");
    private static final Pattern QUALIFIED_REF =
            Pattern.compile("\\b([a-z_][a-z0-9_]*)\\.([a-z_][a-z0-9_]*)\\b");
    private static final Pattern FIRST_TOKEN =
            Pattern.compile("^([a-z_][a-z0-9_]*)");

    /** 这些词跟表名后面时不是别名，而是子句关键字。 */
    private static final Set<String> NOT_AN_ALIAS = Set.of(
            "on", "where", "set", "values", "order", "group", "by", "limit", "offset", "and", "or",
            "left", "right", "inner", "full", "cross", "outer", "join", "from", "as", "using",
            "having", "union", "all", "distinct", "select", "with", "returning");

    /** 建表语句里以这些词开头的行是表级约束，不是列定义。 */
    private static final Set<String> NOT_A_COLUMN = Set.of(
            "constraint", "primary", "unique", "check", "foreign", "key", "exclude");

    @Test
    void MapperXML里的限定列名必须真实存在于DDL() {
        Map<String, Set<String>> schema = tableColumns(readAllMigrations());
        assertThat(schema).as("没解析出任何表，测试本身失效").isNotEmpty();

        Map<String, String> problems = new LinkedHashMap<>();
        Set<String> tablesChecked = new LinkedHashSet<>();
        int checked = 0;
        for (Map.Entry<String, String> file : mapperXmlFiles().entrySet()) {
            String xml = stripComments(file.getValue());
            Matcher statements = STATEMENT.matcher(xml);
            while (statements.find()) {
                String tag = statements.group(1).toLowerCase();
                String body = statements.group(2);
                Map<String, String> aliasToTable = aliasesOf(body);
                if (aliasToTable.isEmpty()) {
                    continue;
                }
                Matcher refs = QUALIFIED_REF.matcher(body);
                while (refs.find()) {
                    String alias = refs.group(1);
                    String column = refs.group(2);
                    String table = aliasToTable.get(alias);
                    if (table == null) {
                        // 不是表别名：包名、DTO 全名、foreach 变量等
                        continue;
                    }
                    checked++;
                    tablesChecked.add(table);
                    Set<String> columns = schema.get(table);
                    String where = file.getKey() + " 里的 <" + tag + ">：" + alias + "." + column;
                    if (columns == null) {
                        problems.putIfAbsent(where, "表 " + table + " 在迁移脚本里找不到");
                    } else if (!columns.contains(column)) {
                        problems.putIfAbsent(where, "表 " + table + " 没有列 " + column);
                    }
                }
            }
        }

        // 自检：解析不到任何引用时下面那条断言会恒真，等于没测。这里不只数个数，
        // 而是点名"O-21 要保护的那几张跨域表"必须真的被扫到 —— 数量门槛无法区分
        // "扫全了" 与 "只扫到本模块的 3 个 XML"（jar/dir 两种 classpath 形态各踩过一次）。
        assertThat(tablesChecked)
                .as("跨域语句没被解析到：这些表正是 O-21 要保护的对象（扫到 %d 处引用 / %d 个 XML）",
                        checked, mapperXmlFiles().size())
                .contains("workflow_step", "workflow_version", "workflow", "operator_version", "task_step");
        // 一次报全部：不必"修一条跑一次"
        assertThat(problems)
                .as("Mapper XML 与 DDL 不一致：这些列在库里不存在，SQL 一执行就是 500")
                .isEmpty();
    }

    // ── DDL 侧 ──────────────────────────────────────────────

    private Map<String, Set<String>> tableColumns(String ddl) {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        Matcher create = CREATE_TABLE.matcher(ddl);
        while (create.find()) {
            String table = create.group(1).toLowerCase();
            int start = create.end();
            int end = matchingParen(ddl, start);
            tables.computeIfAbsent(table, k -> new LinkedHashSet<>())
                    .addAll(columnsOf(ddl.substring(start, end)));
        }
        // 后续版本追加的列：只看 V1 的 CREATE TABLE 会把它们全判成"不存在"
        Matcher alter = ALTER_ADD_COLUMN.matcher(ddl);
        while (alter.find()) {
            tables.computeIfAbsent(alter.group(1).toLowerCase(), k -> new LinkedHashSet<>())
                    .add(alter.group(2).toLowerCase());
        }
        return tables;
    }

    /** 从 {@code start} 起找到与之配对的 {@code )}（跳过字符串字面量）。 */
    private int matchingParen(String text, int start) {
        int depth = 1;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                inString = !inString;
            } else if (!inString) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')' && --depth == 0) {
                    return i;
                }
            }
        }
        return text.length();
    }

    /** 取建表块里的列名：按顶层逗号切段，每段首个标识符即列名（约束行被黑名单滤掉）。 */
    private Set<String> columnsOf(String block) {
        Set<String> columns = new LinkedHashSet<>();
        for (String segment : splitTopLevel(block)) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Matcher first = FIRST_TOKEN.matcher(trimmed);
            if (first.find()) {
                columns.add(first.group(1).toLowerCase());
            }
        }
        columns.removeAll(NOT_A_COLUMN);
        return columns;
    }

    /** 按"括号深度 0 处"的逗号切分：{@code varchar(32)} / {@code CHECK (a IN ('x','y'))} 不能被切开。 */
    private List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\'') {
                inString = !inString;
            }
            if (!inString) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(ch);
        }
        parts.add(current.toString());
        return parts;
    }

    /** 拼起全部迁移脚本（建表在 V1，后续版本负责补列）。 */
    private String readAllMigrations() {
        List<String> names = migrationFiles();
        assertThat(names).as("没找到任何迁移脚本（路径变了？）").isNotEmpty();
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
                assertThat(in).as("迁移脚本读不到: %s", name).isNotNull();
                sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            } catch (IOException e) {
                throw new IllegalStateException("读取迁移脚本失败: " + name, e);
            }
        }
        // SQL 行注释里会出现表名/列名（"workflow_step 没有 deleted 列"），必须先去注释再解析
        return sb.toString().replaceAll("(?m)--.*$", "");
    }

    private List<String> migrationFiles() {
        URL dir = getClass().getClassLoader().getResource("db/migration");
        List<String> names = new ArrayList<>();
        if (dir == null || !"file".equals(dir.getProtocol())) {
            return names;
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

    // ── Mapper XML 侧 ───────────────────────────────────────

    /** 相对路径 → XML 内容（{@code mapper} 在 domain 与 server 两个模块下各有一份）。 */
    private Map<String, String> mapperXmlFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        try {
            Enumeration<URL> roots = getClass().getClassLoader().getResources("mapper");
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    // 目录形态：跑 `mvn test`（未打包）时走这条
                    collectFromDirectory(root, files);
                } else if ("jar".equals(root.getProtocol())) {
                    // jar 形态：跑 `mvn verify` 时上游模块已被 package，classpath 上是 jar。
                    // 只认 file: 会让本测试**静默**退化成"只扫本模块的 3 个 XML"——
                    // 这正是自检断言存在的理由（它抓到过一次）。
                    collectFromJar(root, files);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 Mapper XML 失败", e);
        }
        assertThat(files).as("一个 Mapper XML 都没找到（classpath 里没有 mapper/？）").isNotEmpty();
        return files;
    }

    private void collectFromDirectory(URL root, Map<String, String> files) throws Exception {
        Path rootPath = Path.of(root.toURI());
        try (Stream<Path> paths = Files.walk(rootPath)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                put(files, rootPath.relativize(path).toString(),
                        Files.readString(path, StandardCharsets.UTF_8), path.toString());
            }
        }
    }

    private void collectFromJar(URL root, Map<String, String> files) throws Exception {
        JarURLConnection connection = (JarURLConnection) root.openConnection();
        // 不缓存：Windows 上缓存会持有文件句柄，后续 clean 可能删不掉
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
            for (JarEntry entry : jar.stream()
                    .filter(e -> !e.isDirectory() && e.getName().startsWith("mapper/")
                            && e.getName().endsWith(".xml"))
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList()) {
                String content;
                try (InputStream in = jar.getInputStream(entry)) {
                    content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                put(files, entry.getName().substring("mapper/".length()), content,
                        jar.getName() + "!/" + entry.getName());
            }
        }
    }

    /** 相对路径撞名时退回全路径，保证不漏文件（两个模块的 {@code asset/} 目录同名但不同文件）。 */
    private void put(Map<String, String> files, String key, String content, String fullPath) {
        if (files.putIfAbsent(key, content) != null) {
            files.put(fullPath, content);
        }
    }

    /** 去掉 XML 注释与 SQL 行注释：注释里的散文（"workflow_step 没有 deleted 列"）全是误报源。 */
    private String stripComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", " ").replaceAll("(?m)--.*$", " ");
    }

    private Map<String, String> aliasesOf(String sql) {
        Map<String, String> aliases = new HashMap<>();
        Matcher matcher = TABLE_REF.matcher(sql);
        while (matcher.find()) {
            String alias = matcher.group(3);
            if (alias == null || NOT_AN_ALIAS.contains(alias.toLowerCase())) {
                continue;
            }
            aliases.put(alias.toLowerCase(), matcher.group(2).toLowerCase());
        }
        return aliases;
    }
}
