package com.tong.ziwen.ai.service;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 开机入库（RAG 的写路径）：把 data/poems.json 里的每首诗向量化后放进向量库。
 *
 * <p>内存向量库重启即空，所以每次启动都全量重嵌一遍——每次都重新调用 embedding
 * 接口、重新产生费用。语料变大后"重启重嵌"就不划算了，这个问题留给下一小篇解决。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PoemIngestor implements ApplicationRunner {

    private final PoemService poemService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Override
    public void run(ApplicationArguments arguments) {
        List<TextSegment> segments = poemService.toSegments();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        log.info("古诗存入内存向量库完成：{} 首（重启自动重建）", segments.size());
    }
}
