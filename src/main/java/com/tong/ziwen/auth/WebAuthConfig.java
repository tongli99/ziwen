package com.tong.ziwen.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * 拦截器注册：/api/** 全量要求登录，白名单只放行注册/登录/注销三个入口。
 *
 * <p>静态页面（login.html 登录页、index.html 对话页）不在 /api 下，无需登录即可
 * 打开——先看得到登录页，才谈得上登录。这是"页面可达、接口受保护"的常见布局。</p>
 *
 * <p>本类同时认领 {@link AuthProperties} 的注册：谁消费这份配置谁注册，
 * 主类不集中代办。</p>
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(AuthProperties.class)
public class WebAuthConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/register", "/api/auth/login", "/api/auth/logout");
    }
}
