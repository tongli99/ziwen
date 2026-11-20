package com.tong.ziwen.ai.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 装配：embedding 模型、向量库、检索器、增强器。
 *
 * <p>这里没有新增任何 Maven 依赖——embedding 走的仍是 OpenAI 兼容协议
 * （langchain4j-open-ai 同一个模块），内存向量库和检索组件都在 langchain4j 核心里。</p>
 */
@Configuration
@RequiredArgsConstructor
public class RagConfig {

    /**
     * 检索结果注入 prompt 的模板：必须包含 {{userMessage}} 和 {{contents}} 两个占位符。
     * 框架默认模板是英文（"Answer using the following information..."），给娃的产品换成中文。
     */
    private static final String CONTENT_TEMPLATE = """
            {{userMessage}}

            下面是从古诗书里查到的资料，回答古诗问题以查到的诗为准：
            {{contents}}""";

    private final AiProperties properties;

    /**
     * embedding 模型：由独立的 embedding 配置构建，与 chat 模型可以不是同一家提供方。
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        AiProperties.Embedding embedding = properties.getEmbedding();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embedding.getBaseUrl())
                .apiKey(embedding.getApiKey())
                .modelName(embedding.getModelName())
                .build();
    }

    /**
     * 向量库：v0.2 用 JVM 内存实现，检索是把库里每个向量和问题向量挨个算相似度的精确扫描。
     *
     * <p>零基础设施的教学取舍：全库就几首诗的量级，完全够用；
     * 需要持久化和近似最近邻索引时，替换点就是这个 EmbeddingStore 接口。</p>
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    /** 检索器：把用户问题向量化，去向量库捞最相近的几首诗。 */
    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                             EmbeddingModel embeddingModel) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(properties.getRag().getMaxResults())
                .build();
    }

    /** 增强器：声明式 RAG 的总入口——每轮对话先检索，再把结果按模板拼进这条用户消息。 */
    @Bean
    public RetrievalAugmentor retrievalAugmentor(ContentRetriever contentRetriever) {
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(contentRetriever)
                .contentInjector(DefaultContentInjector.builder()
                        .promptTemplate(PromptTemplate.from(CONTENT_TEMPLATE))
                        .build())
                .build();
    }
}
