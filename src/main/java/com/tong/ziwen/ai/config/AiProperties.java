package com.tong.ziwen.ai.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * AI 配置项，绑定 application.yml 中 {@code ai.*} 前缀。
 *
 * <p>{@link Validated} 让缺 key / 缺地址这类配置错误在启动时直接暴露，
 * 而不是等到第一次请求才抛异常。</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 对话模型配置 */
    @Valid
    private Chat chat = new Chat();

    /** 多轮对话记忆配置 */
    private Memory memory = new Memory();

    /** 对话模型连接与采样参数（base-url 指向任意 OpenAI 兼容端点）。 */
    @Data
    public static class Chat {
        @NotBlank
        private String baseUrl;
        @NotBlank
        private String apiKey;
        @NotBlank
        private String modelName;
        private Double temperature;
    }

    @Data
    public static class Memory {
        /** 每个会话保留的最大消息条数 */
        private int maxMessages = 20;

        /** Redis key 前缀，完整 key = 前缀 + sessionId，统一前缀方便按业务清点/清理。 */
        private String keyPrefix = "ziwen:chat:memory:";

        /**
         * 会话记忆的存活时间：滑动过期——每轮对话写回时刷新，长期不聊的会话自动清理。
         */
        @DurationUnit(ChronoUnit.DAYS)
        private Duration ttl = Duration.ofDays(7);
    }
}
