package com.tong.ziwen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 字问——陪娃学习的 AI 替身，Spring Boot 启动入口。
 *
 * <p>{@code @ConfigurationProperties} 的注册不放主类、放各模块自己的配置类
 * （AiConfig 认领 AiProperties、WebAuthConfig 认领 AuthProperties）：主类上的
 * 注册会随主类进入任何局部装配场景，只想装配一部分的场合被迫背上全部配置的
 * 绑定与校验，模块边界也糊了。主类只做组件扫描与启动两件事。</p>
 */
@SpringBootApplication
public class ZiwenApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZiwenApplication.class, args);
    }
}
