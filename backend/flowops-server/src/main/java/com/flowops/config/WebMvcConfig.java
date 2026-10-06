package com.flowops.config;

import com.flowops.common.web.TraceIdFilter;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.flowops.common.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：traceId 过滤器、Sa-Token 登录校验、UserContext 填充、CORS。
 */
@Configuration
public class WebMvcConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>(new TraceIdFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /** JSON 请求体缓存（幂等切面指纹依赖，docs/07 §7.2）。 */
    @Bean
    public FilterRegistrationBean<com.flowops.modules.governance.aspect.CachedBodyRequestFilter> cachedBodyFilter() {
        FilterRegistrationBean<com.flowops.modules.governance.aspect.CachedBodyRequestFilter> reg =
                new FilterRegistrationBean<>(new com.flowops.modules.governance.aspect.CachedBodyRequestFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    @Bean
    public WebMvcConfigurer flowopsMvcConfigurer(com.flowops.modules.auth.scope.DataScopeResolver dataScopeResolver) {
        return new WebMvcConfigurer() {

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new SaInterceptor(handler ->
                                SaRouter.match("/**")
                                        .notMatch("/auth/login", "/actuator/**", "/error", "/favicon.ico", "/internal/**")  // 内网端点（心跳），网络边界即鉴权边界
                                        .check(r -> StpUtil.checkLogin())))
                        .addPathPatterns("/**");

                registry.addInterceptor(new UserContextInterceptor(dataScopeResolver))
                        .addPathPatterns("/**");
            }

            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // 开发期 Vite 代理同源，此处仅放行本地前端直连调试；生产经 Nginx 同源
                registry.addMapping("/**")
                        .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders(TraceIdFilter.HEADER)
                        .maxAge(3600);
            }
        };
    }
}
