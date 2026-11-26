package com.tong.ziwen;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.tong.ziwen.ai.store.QdrantCollectionAdmin;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

/**
 * 上下文冒烟测试：只验证所有 Bean（AiConfig / RagConfig / Controller…）能装配成功。
 *
 * <p>不依赖真实模型，也不依赖真实 Qdrant：chat 模型构建不发起网络请求；
 * embedding 模型换成固定向量的假实现；向量库换回内存实现、管理操作换 mock——
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

    /**
     * 冒烟测试不连真实 Qdrant：向量库退回内存实现，真实 Qdrant 链路按 README 的步骤验证。
     * 这里故意让管理端报告"0 条"，开机入库于是走"嵌入并写入"分支，整条写路径照常跑一遍。
     */
    @TestConfiguration
    static class InMemoryVectorStoreConfig {

        @Bean
        @Primary
        EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
            return new InMemoryEmbeddingStore<>();
        }
    }

    /** mock 的管理端默认返回 0 条，开机入库于是走"嵌入并写入"分支；建库动作什么都不做。 */
    @MockitoBean
    private QdrantCollectionAdmin collectionAdmin;

    @Test
    void contextLoads() {
    }
}
