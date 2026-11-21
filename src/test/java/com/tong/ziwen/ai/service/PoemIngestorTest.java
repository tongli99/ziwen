package com.tong.ziwen.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tong.ziwen.ai.config.AiProperties;
import com.tong.ziwen.ai.store.QdrantCollectionAdmin;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

/**
 * 开机入库测试：v0.2.1 的核心承诺"库里够了就不重嵌"，三条路径都不依赖真实模型和 Qdrant。
 */
class PoemIngestorTest {

    /** 调了几次 embedAll——"不重嵌"的断言就靠它。 */
    private final AtomicInteger embedCalls = new AtomicInteger();

    /** 假 embedding 模型：所有文本嵌成同一个 3 维向量，顺便记一笔调用次数。 */
    private final EmbeddingModel countingFakeModel = new EmbeddingModel() {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            embedCalls.incrementAndGet();
            return Response.from(segments.stream()
                    .map(segment -> Embedding.from(new float[] {0.1f, 0.2f, 0.3f}))
                    .toList());
        }
    };

    private final EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
    private final QdrantCollectionAdmin collectionAdmin = mock(QdrantCollectionAdmin.class);

    private PoemIngestor ingestor;
    private int poemCount;

    @BeforeEach
    void setUp() {
        PoemService poemService = new PoemService(
                new ObjectMapper(), countingFakeModel, embeddingStore, new AiProperties());
        poemCount = poemService.toSegments().size();
        ingestor = new PoemIngestor(poemService, countingFakeModel, embeddingStore, collectionAdmin);
    }

    @Test
    void skipsEmbeddingWhenStoreAlreadyHasEnoughPoems() {
        // 重启场景：库里条数已够，不该再调一次 embedding
        when(collectionAdmin.countPoints()).thenReturn((long) poemCount);

        ingestor.run(null);

        assertThat(embedCalls.get()).isZero();
        verify(collectionAdmin, never()).recreateCollection(anyInt());
        assertThat(storedSegmentCount()).isZero();
    }

    @Test
    void embedsAndStoresEverythingOnFirstStart() {
        // 首次启动场景：空库（0 条）→ 全量嵌入 → 建库 → 写入
        when(collectionAdmin.countPoints()).thenReturn(0L);

        ingestor.run(null);

        assertThat(embedCalls.get()).isEqualTo(1);
        // 维度从真实 embedding 量出来：假模型是 3 维
        verify(collectionAdmin).recreateCollection(3);
        assertThat(storedSegmentCount()).isEqualTo(poemCount);
    }

    @Test
    void reingestsAllWhenCorpusGrewBeyondStoredCount() {
        // 语料加了新诗：库存 1 条 < 语料 N 条 → 重嵌全量（重建 collection，不会重复）
        when(collectionAdmin.countPoints()).thenReturn(1L);

        ingestor.run(null);

        assertThat(embedCalls.get()).isEqualTo(1);
        assertThat(storedSegmentCount()).isEqualTo(poemCount);
    }

    /** 数一下向量库里现在实际存了几条（检索 maxResults 给大，等于全量清点）。 */
    private int storedSegmentCount() {
        return embeddingStore.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(new float[] {1, 0, 0}))
                        .maxResults(100)
                        .build())
                .matches().size();
    }
}
