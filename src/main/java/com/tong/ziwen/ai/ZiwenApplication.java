package com.tong.ziwen.ai;

import com.tong.ziwen.ai.config.AiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 字问——陪娃学习的 AI 替身，Spring Boot 启动入口。 */
@SpringBootApplication
@EnableConfigurationProperties(AiProperties.class)
public class ZiwenApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZiwenApplication.class, args);
    }
}
