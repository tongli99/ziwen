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

    /** embedding 模型配置 */
    @Valid
    private Embedding embedding = new Embedding();

    /** 多轮对话记忆配置 */
    private Memory memory = new Memory();

    /** RAG 检索配置 */
    private Rag rag = new Rag();

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

    /**
     * embedding 模型连接参数。
     *
     * <p>chat 和 embedding 分开配置：有的厂商（如 DeepSeek）只有 chat 没有 embedding 接口；
     * 就算一家能全包，也未必每个能力都最强——分开指向，每个功能用各自最合适的提供方。</p>
     */
    @Data
    public static class Embedding {
        @NotBlank
        private String baseUrl;
        @NotBlank
        private String apiKey;
        @NotBlank
        private String modelName;
    }

    /** RAG 检索参数。 */
    @Data
    public static class Rag {
        /** 每次检索带回的匹配数目：太少容易漏，太多会稀释无关回答。 */
        private int maxResults = 3;
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
