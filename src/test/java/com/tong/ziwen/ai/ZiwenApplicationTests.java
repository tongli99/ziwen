package com.tong.ziwen.ai;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

/**
 * 上下文冒烟测试：只验证所有 Bean（AiConfig / RagConfig / Controller…）能装配成功。
 *
 * <p>不依赖真实模型：chat 模型构建不发起网络请求；embedding 模型换成固定向量的假实现——
 * {@link SpringApplication} 的 ApplicationRunner 在测试里照样会跑，
 * "开机入库"链路因此也被顺带验证到。</p>
 */
@SpringBootTest(properties = {
        "ai.chat.base-url=http://localhost:9999",
        "ai.chat.api-key=test-key",
        "ai.chat.model-name=test-model",
        "ai.embedding.base-url=http://localhost:9999",
        "ai.embedding.api-key=test-key",
        "ai.embedding.model-name=test-embedding-model"
})
class ZiwenApplicationTests {

    /** 假 embedding 模型：每段文本都嵌成同一个向量，不联网，开机入库链路照常走通。 */
    @TestConfiguration
    static class FixedEmbeddingConfig {

        @Bean
        @Primary
        EmbeddingModel stubEmbeddingModel() {
            return new EmbeddingModel() {
                @Override
                public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                    return Response.from(segments.stream()
                            .map(segment -> Embedding.from(new float[] {0.1f}))
                            .toList());
                }
            };
        }
    }

    @Test
    void contextLoads() {
    }
}
