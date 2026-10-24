package com.flowops.it;

import com.flowops.it.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataScope 越权矩阵（M2 遗留的 DoD-5，README-M3 §4 的 <b>O-9</b>）。
 *
 * <p><b>为什么现在才做得到</b>：M2/M3 时代只有分层单测 —— 拦截器的 SQL 片段、
 * 解析器的可见集、Service 的 40301 各自有断言，但"用真实 token 打一遍全部端点"
 * 需要 PG + Redis + 完整 Spring 上下文，三者当时都不具备。本类在真实 PG16 上
 * 起内嵌容器，用真实登录拿到的 token 遍历端点。</p>
 *
 * <p><b>第一轮实跑就抓到了四个真问题</b>（详见 README-M5 §3）：分页 {@code total}
 * 未受数据权限过滤、三类资源把"越权"报成"不存在"、审计落库必然失败、
 * 以及进程本身根本起不来。这正是"必须在真实环境跑一遍"的理由 ——
 * 它们全都通过了此前全部 648 个单测。</p>
 *
 * <p><b>矩阵口径</b>：{@code it-user-a}（PROJECT_ADMIN，仅 A 项目成员）
 * 访问 B 项目的资源 → 一律 <b>40301</b>，而不是 40300（那是没权限点）
 * 也不是 40400（那是真不存在）。docs/07 §4.2。</p>
 */
class DataScopeMatrixIT extends AbstractIntegrationTest {

    private static final int SCOPE_EXCEEDED = 40301;

    private static String userA;
    private static String ops;
    private static String admin;

    // 非静态 @BeforeAll：依赖基类注入的 TestRestTemplate（PER_CLASS 生命周期下允许），
    // 且 JUnit 保证超类的 @BeforeAll（装夹具）先于本类执行
    @BeforeAll
    void loginAll() {
        userA = login("it-user-a");
        ops = login("it-ops");
        admin = login("admin");
    }

    /** 越权用例：{@code 用户, 方法, 路径, 请求体, 额外请求头}。 */
    static Stream<Arguments> crossProjectCases() {
        return Stream.of(
                // ── 项目 ──
                Arguments.of("a", "GET", "/projects/PRJ-IT-B", null, Map.of()),
                // ── 工作流 / 版本 ──
                Arguments.of("a", "GET", "/workflows/WF-IT-B", null, Map.of()),
                Arguments.of("a", "GET", "/workflows/WF-IT-B/versions", null, Map.of()),
                Arguments.of("a", "GET", "/workflow-versions/WFV-IT-B1", null, Map.of()),
                Arguments.of("a", "PUT", "/workflows/WF-IT-B",
                        Map.of("workflow_name", "改名", "project_id", "PRJ-IT-B"), Map.of()),
                Arguments.of("a", "POST", "/workflows/WF-IT-B/publish",
                        Map.of("version_id", "WFV-IT-B1"),
                        Map.of("Idempotency-Key", "it-publish-cross-project")),
                // ── 算子 / 算子版本 ──
                Arguments.of("a", "GET", "/operators/OP-IT-B", null, Map.of()),
                Arguments.of("a", "GET", "/operators/OP-IT-B/versions", null, Map.of()),
                Arguments.of("a", "GET", "/operator-versions/OPV-IT-B1", null, Map.of()),
                // ── 任务 ──
                Arguments.of("a", "GET", "/tasks/TASK-IT-B1", null, Map.of()),
                Arguments.of("a", "GET", "/tasks/TASK-IT-B1/steps", null, Map.of()),
                Arguments.of("a", "GET", "/tasks/TASK-IT-B1/diagnosis", null, Map.of()),
                Arguments.of("a", "POST", "/tasks/TASK-IT-B1/stop",
                        Map.of("stop_reason", "越权停止探测用的原因串"), Map.of()),
                Arguments.of("a", "POST", "/tasks/TASK-IT-B1/retry", null, Map.of()),
                Arguments.of("a", "POST", "/tasks/TASK-IT-B1/rerun-failed", Map.of(), Map.of()),
                // ── 回填 ──
                Arguments.of("a", "GET", "/backfills/BF-IT-B1", null, Map.of()),
                Arguments.of("a", "GET", "/backfills/BF-IT-B1/tasks", null, Map.of()),
                Arguments.of("a", "POST", "/backfills/BF-IT-B1/pause", null, Map.of()),
                // ── 触发器 ──
                Arguments.of("a", "GET", "/triggers/TRG-IT-B1", null, Map.of()),
                Arguments.of("a", "PUT", "/triggers/TRG-IT-B1",
                        Map.of("trigger_name", "改名", "trigger_type", "CRON",
                                "cron_expression", "0 0 2 * * *"), Map.of()),
                Arguments.of("a", "DELETE", "/triggers/TRG-IT-B1", null, Map.of()),
                // ── 集群维度（AUTHORIZED_CLUSTER 的 it-ops 只授权了 CL-IT-1）──
                Arguments.of("ops", "GET", "/clusters/CL-IT-2", null, Map.of()),
                Arguments.of("ops", "GET", "/executor-nodes/EN-IT-2", null, Map.of()),
                Arguments.of("ops", "GET", "/queues/QU-IT-2", null, Map.of()),
                Arguments.of("ops", "GET", "/tasks/TASK-IT-B1", null, Map.of())
        );
    }

    @ParameterizedTest(name = "{1} {2}（{0}）→ 40301")
    @MethodSource("crossProjectCases")
    @DisplayName("跨项目/跨集群访问：存在但越权 → 40301（不是 40400）")
    void 越权访问一律40301(String user, String method, String path, Object body, Map<String, String> headers) {
        var resp = switch (method) {
            case "GET" -> get(path, tokenOf(user));
            case "POST" -> exchange(org.springframework.http.HttpMethod.POST, path, tokenOf(user), body, headers);
            case "PUT" -> put(path, tokenOf(user), body);
            default -> delete(path, tokenOf(user));
        };

        assertThat(resp.code())
                .as("%s %s 越权时应回 40301（40300=没权限点、40400=被当成不存在），实际响应=%s",
                        method, path, resp.body())
                .isEqualTo(SCOPE_EXCEEDED);
        assertThat(resp.json().at("/data/resource_type").asText())
                .as("40301 必须带上 resource_type（docs/07 §4.2）")
                .isNotBlank();
    }

    /** 正例对照：本人项目内的资源必须照常可见 —— 否则"全 40301"可能只是"全查不到"。 */
    @ParameterizedTest(name = "{1} {2}（{0}）→ 200")
    @MethodSource("visibleCases")
    @DisplayName("范围对照：本人可见资源不应被误伤")
    void 范围内资源可见(String user, String method, String path) {
        var resp = get(path, tokenOf(user));
        assertThat(resp.code()).as("%s %s 应可见，实际=%s", method, path, resp.body()).isEqualTo(0);
    }

    static Stream<Arguments> visibleCases() {
        return Stream.of(
                Arguments.of("a", "GET", "/projects/PRJ-IT-A"),
                Arguments.of("a", "GET", "/workflows/WF-IT-A"),
                Arguments.of("a", "GET", "/workflows/WF-IT-A/versions"),
                Arguments.of("a", "GET", "/tasks/TASK-IT-A1"),
                Arguments.of("a", "GET", "/tasks/TASK-IT-A1/steps"),
                Arguments.of("a", "GET", "/backfills/BF-IT-A1"),
                Arguments.of("a", "GET", "/operators/OP-IT-A"),
                Arguments.of("ops", "GET", "/clusters/CL-IT-1"),
                Arguments.of("ops", "GET", "/executor-nodes/EN-IT-1"),
                // ALL 范围（平台管理员）不注入任何条件，B 项目也照常可见
                Arguments.of("admin", "GET", "/workflows/WF-IT-B")
        );
    }

    /**
     * 列表端点的 {@code total} 必须与 {@code records} 同口径（O-75 防复发）。
     *
     * <p>只断言 records 是不够的：分页 count 由 {@code PaginationInnerInterceptor}
     * 用原始 Executor 执行，会绕过排在它之后的拦截器 —— 于是 records 干净、
     * total 却把"一共有几个你看不到的资源"泄漏了出去。</p>
     */
    @ParameterizedTest(name = "{0} → 只见本项目 {2}")
    @MethodSource("listCases")
    @DisplayName("列表端点：total 与 records 同时被数据权限过滤")
    void 列表不泄漏他项目(String path, String idField, String expectedOnly) {
        var resp = get(path + "?page=1&pageSize=50", userA);
        assertThat(resp.code()).isEqualTo(0);

        List<String> ids = new java.util.ArrayList<>();
        resp.json().at("/data/records").forEach(n -> ids.add(n.path(idField).asText()));

        assertThat(ids).as("%s 的 records 只应含本项目资源", path).containsExactly(expectedOnly);
        assertThat(resp.json().at("/data/total").asInt())
                .as("%s 的 total 必须同样过滤（O-75：count 曾绕过 DataScope）", path)
                .isEqualTo(1);
    }

    static Stream<Arguments> listCases() {
        return Stream.of(
                Arguments.of("/projects", "project_id", "PRJ-IT-A"),
                Arguments.of("/workflows", "workflow_id", "WF-IT-A"),
                Arguments.of("/tasks", "task_id", "TASK-IT-A1"),
                Arguments.of("/backfills", "batch_id", "BF-IT-A1"),
                Arguments.of("/operators", "operator_id", "OP-IT-A")
        );
    }

    /**
     * 已知越权面（<b>O-79</b>）：凭据未纳入 DataScope 登记制。
     *
     * <p>{@code credential} 表<b>没有</b>登记到 {@code FlowopsDataPermissionHandler}
     * （它不在 PROJECT/CLUSTER 两张登记表里），且 {@code CredentialService} 也没有
     * 任何范围判定 —— 于是 PROJECT 范围用户可以读到他项目的凭据。
     * docs/07 §5.4 给凭据写的是 "AUTHORIZED_CLUSTER / PROJECT"，即<b>应当</b>按项目过滤。</p>
     *
     * <p><b>为什么不顺手修</b>：{@code credential.project_id} <b>可空</b>（平台级凭据），
     * 一旦登记 PROJECT 维度，{@code project_id IS NULL} 的平台级凭据会对所有 PROJECT
     * 用户消失，而执行节点绑定的很可能正是平台级凭据 —— 那会让"节点详情看不到凭据"
     * 变成新的故障。这个口径需要先定（平台级凭据谁可见？按集群授权？）再实现。</p>
     *
     * <p>这里断言<b>当前行为</b>而不是期望行为，是刻意的：把它固化成一个会红的断言，
     * 将来一旦有人改了登记制，本测试立刻提示"重新评估 O-79"，
     * 比留一句注释可靠 —— 注释不会自己变红。</p>
     */
    @Test
    @DisplayName("O-79 已知越权面：凭据未纳入 DataScope，PROJECT 用户可读他项目凭据")
    void 已知越权面_凭据未纳入数据范围登记制() {
        var resp = get("/credentials/CR-IT-2", userA);

        assertThat(resp.code())
                .as("O-79：这是已知缺口，不是期望行为。若本断言变红，说明凭据已被纳入数据范围，"
                        + "请回头关闭 README-M5 的 O-79 登记并把这里改成 40301")
                .isEqualTo(0);
    }

    private static String tokenOf(String user) {
        return switch (user) {
            case "a" -> userA;
            case "ops" -> ops;
            default -> admin;
        };
    }
}
