package com.flowops.it.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/**
 * 集成测试基类（O-9 / O-18 的共同前提）。
 *
 * <p><b>为什么现在才有它</b>：此前项目里一个 {@code @SpringBootTest} 都没有 ——
 * 648 个用例全是分层单测。后果是"进程能不能起来"这件事<b>从未被任何一道关卡验证过</b>
 * （CI 只有 Redis、没有 PG，单测不起 Spring 上下文）。本基类第一次把
 * 「Flyway 8 个脚本 + 全部 Bean + 内嵌容器 + MVC 映射」放到真实 PG 上跑一遍。</p>
 *
 * <p><b>为什么用 RANDOM_PORT（真实内嵌容器）而不是 MOCK / MockMvc</b>：试过 MockMvc，
 * 卡在 {@code ServerEndpointExporter} —— 它要求 ServletContext 上有
 * {@code jakarta.websocket.server.ServerContainer}，而 MOCK 环境没有，
 * 直接 {@code IllegalStateException} 打挂上下文。可选做法是 {@code @MockBean} 把它
 * 换成假实现，但那等于"为了跑测试把真实组件摘掉"；而本测试要回答的第一个问题恰恰是
 * <b>"生产进程能不能起来"</b> —— 摘掉组件后得出的"能起来"是假结论。
 * 真端口上跑真 HTTP，顺带把 WebSocket 端点导出、过滤器链、Sa-Token 拦截器
 * 一并纳入验证范围。</p>
 *
 * <p><b>环境缺失的处理</b>：由 {@link IntegrationEnvironment} 在容器级跳过，
 * 不是在这里 try/catch —— 见该类的注释（Spring 上下文早于 {@code @BeforeAll} 创建）。</p>
 *
 * <p><b>夹具为什么走 {@code ScriptUtils} 而不是 JdbcTemplate</b>：项目铁律是
 * "SQL 与 Java 分离"。夹具 SQL 放在 {@code it/fixtures.sql} 里逐行可评审，
 * Java 侧只负责"执行这个文件"。夹具刻意绕过业务校验（理由见该文件顶部注释），
 * 生产路径不受影响。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@ExtendWith(IntegrationEnvironment.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractIntegrationTest {

    /** 种子管理员与各夹具用户共用此密码（fixtures.sql 复用了 admin 的 BCrypt 串）。 */
    protected static final String IT_PASSWORD = "Admin@123";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected RedisConnectionFactory redisConnectionFactory;

    @LocalServerPort
    protected int port;

    @BeforeAll
    void prepareItEnvironment() throws Exception {
        flushRedisDb();
        loadFixtures();
    }

    /** Redis db 9 是本测试专用 index（见 application-it.yml），刷它不影响开发机上的其他库。 */
    private void flushRedisDb() {
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    private void loadFixtures() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("it/fixtures.sql"));
        }
    }

    /**
     * 真实登录取 token（不走"手工造 UserContext"的捷径）。
     *
     * <p>刻意走 HTTP：Sa-Token 会话、权限点快照、DataScope 解析全部依赖登录时写进
     * Session 的东西。手工塞 ThreadLocal 会绕过这一整段，测出来的越权结论不可信。</p>
     */
    protected String login(String username) {
        ItResponse resp = post("/auth/login", null, Map.of("username", username, "password", IT_PASSWORD));
        if (resp.code() != 0) {
            throw new AssertionError("登录失败 username=" + username + " body=" + resp.body());
        }
        return resp.json().at("/data/token_value").asText();
    }

    // ── HTTP 糖 ────────────────────────────────────────────────────

    protected ItResponse get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    protected ItResponse post(String path, String token, Object body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    protected ItResponse put(String path, String token, Object body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    protected ItResponse delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, token, null);
    }

    protected ItResponse exchange(HttpMethod method, String path, String token, Object body) {
        return exchange(method, path, token, body, Map.of());
    }

    /**
     * @param extraHeaders 额外的请求头（如高危端点强制的 {@code Idempotency-Key}）。
     */
    protected ItResponse exchange(HttpMethod method, String path, String token, Object body,
                                  Map<String, String> extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            // Sa-Token 配置：token-name=Authorization、token-prefix=Bearer
            headers.set("Authorization", "Bearer " + token);
        }
        extraHeaders.forEach(headers::set);
        String payload = body == null ? null : write(body);
        ResponseEntity<String> resp = rest.exchange(url(path), method,
                new HttpEntity<>(payload, headers), String.class);
        return new ItResponse(resp.getStatusCode().value(), resp.getBody());
    }

    protected String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static String write(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一次 HTTP 往返的快照：HTTP 状态码 + 原始 body + 解析后的 JSON。 */
    public record ItResponse(int status, String body) {

        public JsonNode json() {
            try {
                return JSON.readTree(body == null ? "{}" : body);
            } catch (Exception e) {
                throw new AssertionError("响应不是合法 JSON: " + body, e);
            }
        }

        /** 线协议的业务码（body.code，与 HTTP status 语义一致）。 */
        public int code() {
            JsonNode node = json().get("code");
            return node == null ? Integer.MIN_VALUE : node.asInt();
        }
    }
}
