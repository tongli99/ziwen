package com.tong.ziwen.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import lombok.RequiredArgsConstructor;

/**
 * RAG 底座装配：embedding 模型 + Qdrant 向量库。
 *
 * <p>v0.2 的固定检索管线（{@code EmbeddingStoreContentRetriever} +
 * {@code DefaultRetrievalAugmentor}，每轮对话先检索、把资料拼进消息）在 v0.3 退役：
 * 检索改由模型按需调用 {@code PoemSearchTool} 触发。底座不变——工具走的仍是这里
 * 装配的同一个 {@code embeddingStore}，换的是编排方式，不是检索能力。</p>
 */
@Configuration
@RequiredArgsConstructor
public class RagConfig {

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
}
