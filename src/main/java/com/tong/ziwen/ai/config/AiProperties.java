package com.tong.ziwen.ai.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

    /**
     * RAG 检索配置。
     */
    @Valid
    private Rag rag = new Rag();

    /**
     * 工具循环护栏配置（v0.3.2 起）。
     */
    @Valid
    private Tool tool = new Tool();

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

        /**
         * 分诊（RouterAgent）的采样温度，具体取值见 application.yml。对话模型的 temperature 带随机性，
         * 用来分类会让同一句话每次归到不同的类，验收与评测都掺进噪声，所以分诊单独配一个。
         */
        private Double routerTemperature;
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

        /**
         * 相似度阈值：低于它的命中一律丢弃，剩下的才会作为“查到了”。取值限定在
         * [0,1]（RelevanceScore 尺度），越界由 {@code @DecimalMin}/{@code @DecimalMax} 启动即拦截。
         */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double minScore = 0.78;

        /** 向量库 Qdrant 连接参数（v0.2.1 起）。 */
        @Valid
        private Qdrant qdrant = new Qdrant();
    }

    /**
     * Qdrant 连接参数。
     *
     * <p>LangChain4j 的 Qdrant 客户端走 gRPC（默认端口 6334）</p>
     */
    @Data
    public static class Qdrant {
        @NotBlank
        private String host = "localhost";

        /** gRPC 端口。本地 compose 用默认 6334；Qdrant Cloud 的地址端口固定 6334、必须 TLS。 */
        private int port = 6334;

        /** collection 名。类比"一张表"：一个 collection 存一套语料的向量。 */
        @NotBlank
        private String collectionName = "ziwen_poems";

        /** 本地 compose 走明文 gRPC（false）；Qdrant Cloud 必须 true。 */
        private boolean useTls = false;

        /** API key：本地无鉴权的 Qdrant 留空即可，仅 Qdrant Cloud 需要。 */
        private String apiKey = "";
    }

    @Data
    public static class Memory {
        /**
         * 每个会话保留的最大消息条数。带工具的一轮对话占 4 条（问题、工具调用请求、
         * 工具返回结果、回答），v0.3.2 起放宽到 30：窗口越大保留的上下文越多，
         * 代价是每轮请求重发的 token 也越多。
         */
        private int maxMessages = 30;

        /** Redis key 前缀，完整 key = 前缀 + sessionId，统一前缀方便按业务清点/清理。 */
        private String keyPrefix = "ziwen:chat:memory:";

        /**
         * 会话记忆的存活时间：滑动过期，每轮对话写回时刷新，长期不活跃的会话自动清理。
         */
        @DurationUnit(ChronoUnit.DAYS)
        private Duration ttl = Duration.ofDays(7);
    }

    /**
     * Agentic 工具护栏（v0.3.2 起）：软限制（返回劝停文本）与硬限制（框架层异常终止）两级防护。
     */
    @Data
    public static class Tool {
        /** 单轮对话内允许的工具调用次数上限：超出后返回劝停文本，引导模型正常结束本轮对话而非抛出异常。 */
        private int maxCallsPerTurn = 3;

        /** 框架层兜底（AiServices 的 maxToolCallingRoundTrips）：单轮中带工具调用的回复轮数超过该值即抛出异常，终止本轮。 */
        private int maxRoundTrips = 10;
    }
}
