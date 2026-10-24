package com.flowops.it;

import com.flowops.it.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文冒烟（O-9 的前置闸门）。
 *
 * <p><b>它存在的全部理由</b>：本项目在 M4 收官时仍<b>没有任何一个</b>
 * {@code @SpringBootTest}——648 个用例全是分层单测，于是"FlowopsServerApplication
 * 能不能起来"从未被验证过。这个测试把三件事钉死：</p>
 * <ol>
 *   <li>8 个 Flyway 脚本在真实 PG 16 上执行成功（此前一次都没跑过真库）；</li>
 *   <li>全部 Bean 装配完成、内嵌容器启动、MVC 映射无歧义（重复映射会当场抛
 *       {@code Ambiguous mapping}，是"启动即死"级别的问题）；</li>
 *   <li>登录闭环可用（Sa-Token 会话落真实 Redis，权限点 49 个随会话返回）。</li>
 * </ol>
 */
class ContextStartupIT extends AbstractIntegrationTest {

    // 必须按名注入：actuator 还注册了一个 controllerEndpointHandlerMapping，
    // 按类型注入会遇到两个候选（NoUniqueBeanDefinitionException）。
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("登录闭环：种子管理员可取 token，/auth/me 回显身份与 49 个权限点")
    void adminLoginRoundTrip() {
        String token = login("admin");

        var resp = get("/auth/me", token);
        assertThat(resp.code()).as("/auth/me 应成功: %s", resp.body()).isEqualTo(0);
        assertThat(resp.json().at("/data/username").asText()).isEqualTo("admin");
        assertThat(resp.json().at("/data/permissions").size()).isEqualTo(49);
    }

    @Test
    @DisplayName("MVC 映射表：无重复（重复映射=启动即死，且分层单测永远发现不了）")
    void noAmbiguousMappings() {
        Map<String, List<String>> byPattern = new TreeMap<>();
        handlerMapping.getHandlerMethods().forEach((RequestMappingInfo info, HandlerMethod method) -> {
            // produces 必须进 key：BasicErrorController 之类会为同一路径注册
            // "text/html" 与 "application/json" 两个方法（内容协商），那是合法的多态，不是歧义
            String key = info.getMethodsCondition() + " " + info.getPatternValues()
                    + " produces=" + info.getProducesCondition() + " consumes=" + info.getConsumesCondition();
            byPattern.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(method.getMethod().toGenericString());
        });

        List<String> duplicates = byPattern.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " → " + e.getValue())
                .toList();

        assertThat(duplicates)
                .as("同一 HTTP 方法 + 路径不得映射到多个处理器方法（Spring 上下文会因此启动失败）")
                .isEmpty();
        assertThat(byPattern).as("端点总数应随实现增长，空说明 MVC 未装配").isNotEmpty();
    }
}
