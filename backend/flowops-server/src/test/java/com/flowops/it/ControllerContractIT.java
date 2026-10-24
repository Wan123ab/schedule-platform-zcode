package com.flowops.it;

import com.flowops.it.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * controller / 切面 / 全局异常层的契约测试（<b>O-18</b>）。
 *
 * <p><b>O-18 的原文口径</b>：覆盖率门禁刻意不统计 controller/aspect/ws，
 * 因为对它们写"调一遍方法、断言返回对象非空"的单测只能得到假覆盖率；
 * 真正要验的是<b>鉴权、参数绑定、错误码映射</b>，那是集成测试的活。
 * 本类就是那个"活" —— 全部走真实 HTTP，不 mock 任何 controller。</p>
 *
 * <p>与 {@link DataScopeMatrixIT} 的分工：那边验"看见了不该看见的没有"，
 * 这边验"没带身份/没带权限/参数不对时，回去的是不是约定的错误码与形状"。</p>
 */
class ControllerContractIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("未登录访问受保护端点 → 40100（不是 50000）")
    void 未登录() {
        var resp = get("/workflows", null);

        assertThat(resp.code())
                .as("未登录应回 40100；若回 50000 说明 Sa-Token 的 NotLoginException 没人接管，"
                        + "实际响应=%s", resp.body())
                .isEqualTo(40100);
    }

    @Test
    @DisplayName("已登录但无该权限点 → 40300，并回带 required 权限点")
    void 无权限点() {
        // it-user-a 是 PROJECT_ADMIN：docs/07 §5.2 明确"插队仅平台管理员/运维"
        String token = login("it-user-a");

        var resp = post("/tasks/TASK-IT-A1/enqueue-front", token, null);

        assertThat(resp.code()).as("实际响应=%s", resp.body()).isEqualTo(40300);
        assertThat(resp.json().at("/data/required").asText()).isEqualTo("schedule:task:enqueue_front");
    }

    @Test
    @DisplayName("@Valid 校验失败 → 40001 + errors[{field,message}]")
    void 参数校验失败() {
        String token = login("admin");

        var resp = post("/auth/login", null, Map.of("username", "", "password", ""));

        assertThat(resp.code()).as("实际响应=%s", resp.body()).isEqualTo(40001);
        assertThat(resp.json().at("/data/errors").isArray()).isTrue();
        assertThat(resp.json().at("/data/errors").size()).isEqualTo(2);
        assertThat(resp.json().at("/data/errors/0/field").asText()).isNotBlank();
        assertThat(resp.json().at("/data/errors/0/message").asText()).isNotBlank();
    }

    @Test
    @DisplayName("请求体缺失 → 40001（O-78：此前会掉进兜底分支变 50000）")
    void 请求体缺失() {
        String token = login("admin");

        var resp = post("/workflows/WF-IT-A/publish", token, null);

        assertThat(resp.code())
                .as("缺 body 是调用方问题，必须落 4xxxx 而不是 50000，实际响应=%s", resp.body())
                .isEqualTo(40001);
    }

    @Test
    @DisplayName("路径参数类型不匹配 → 40002（PARAM_FORMAT）")
    void 参数格式错误() {
        String token = login("admin");

        // pageSize 是 long，给一个非数字
        var resp = get("/workflows?pageSize=abc", token);

        assertThat(resp.code()).as("实际响应=%s", resp.body()).isEqualTo(40002);
    }

    @Test
    @DisplayName("不存在的路径 → 40400（被 NoResourceFoundException 接管，不是裸 404 页）")
    void 路径不存在() {
        String token = login("admin");

        var resp = get("/definitely-not-a-flowops-endpoint", token);

        assertThat(resp.code()).as("实际响应=%s", resp.body()).isEqualTo(40400);
        assertThat(resp.json().at("/data/resource_type").asText()).isEqualTo("path");
    }

    @Test
    @DisplayName("统一响应形状：code/message/trace_id，且 X-Trace-Id 回写响应头")
    void 响应形状与链路追踪() {
        String token = login("admin");

        var resp = get("/auth/me", token);

        assertThat(resp.code()).isEqualTo(0);
        assertThat(resp.json().has("code")).isTrue();
        assertThat(resp.json().has("message")).isTrue();
        assertThat(resp.json().get("trace_id"))
                .as("线协议要求响应体带 trace_id（docs/07 §3.3）")
                .isNotNull();
        assertThat(resp.json().get("trace_id").asText()).hasSize(32);
    }

    @Test
    @DisplayName("审计真的落进了库（O-76 防复发：AuditAspect 自吞异常，落库失败只留一行日志）")
    void 审计落库() throws Exception {
        // 登录本身就是必审动作（docs/07 §7.3 LOGIN），前面的用例已经触发过若干次
        login("admin");

        long rows;
        long loginRows;
        try (Connection conn = dataSource.getConnection()) {
            rows = countBy(conn, "SELECT count(*) FROM audit_log");
            loginRows = countBy(conn, "SELECT count(*) FROM audit_log WHERE action = 'LOGIN'");
        }

        assertThat(rows)
                .as("O-76：audit_log 实体曾多出一个表里没有的 created_by 列，导致每条审计都插入失败、"
                        + "而 AuditAspect 把异常吞了 —— 表现是'审计功能看着正常、库里一条没有'")
                .isPositive();
        assertThat(loginRows).isPositive();
    }

    private static long countBy(Connection conn, String sql) throws Exception {
        try (var st = conn.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
