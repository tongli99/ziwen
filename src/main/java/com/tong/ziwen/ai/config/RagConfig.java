package com.tong.ziwen.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import lombok.RequiredArgsConstructor;

/**
 * RAG 装配：embedding 模型、向量库、检索器、增强器。
 *
 * <p>v0.2.1 起向量库从 JVM 内存换成 Qdrant（{@code langchain4j-qdrant} 模块），
 * 换的只有 {@code embeddingStore} 这一个 bean；
 * 检索器（{@code EmbeddingStoreContentRetriever}）、增强器（{@code DefaultRetrievalAugmentor}）不变，
 * 因为它们面向的都是 {@code EmbeddingStore} 接口，这正是 v0.2 留好的替换点。
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
     * Qdrant 客户端：存取向量（EmbeddingStore）和建库清点（QdrantCollectionAdmin）共用一个。
     *
     * <p>构造它只是准备好 gRPC 通道，不会真的连 Qdrant——连不上要到第一次调用才暴露，
     * 所以"Qdrant 没起就启动应用"的错误出现在开机入库那一步，而不是这里。</p>
     */
    @Bean
    public QdrantClient qdrantClient() {
        AiProperties.Qdrant qdrant = properties.getRag().getQdrant();
        QdrantGrpcClient.Builder grpcBuilder = QdrantGrpcClient
                .newBuilder(qdrant.getHost(), qdrant.getPort(), qdrant.isUseTls());
        if (StringUtils.hasText(qdrant.getApiKey())) {
            grpcBuilder.withApiKey(qdrant.getApiKey());
        }
        return new QdrantClient(grpcBuilder.build());
    }

    /**
     * 向量库：Qdrant 独立进程存储，重启不丢；检索走 HNSW 近似最近邻索引。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(QdrantClient qdrantClient) {
        return QdrantEmbeddingStore.builder()
                .client(qdrantClient)
                .collectionName(properties.getRag().getQdrant().getCollectionName())
                .build();
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
