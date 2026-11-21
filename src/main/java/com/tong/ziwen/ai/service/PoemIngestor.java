package com.tong.ziwen.ai.service;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.tong.ziwen.ai.store.QdrantCollectionAdmin;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 开机入库（RAG 的写路径）：把 data/poems.json 里的每首诗向量化后放进向量库。
 *
 * <p>v0.2.1 起向量落在 Qdrant，重启不清空，所以这里先数一下库里已有几首：
 * 够了就直接跳过——本次启动 0 次 embedding 调用，这就是"重启不重嵌"。
 * 不够（首次启动，或语料加了新诗）才重新嵌入全量并重建 collection。</p>
 *
 * <p>判断只认条数、不认内容：改了已有诗的文字不会触发重嵌（条数没变），
 * 想强制重灌就删掉 collection 再重启（见 http/qdrant.http）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PoemIngestor implements ApplicationRunner {

    private final PoemService poemService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final QdrantCollectionAdmin collectionAdmin;

    @Override
    public void run(ApplicationArguments arguments) {
        List<TextSegment> segments = poemService.toSegments();
        long storedCount = collectionAdmin.countPoints();
        if (storedCount >= segments.size()) {
            log.info("向量库已有 {} 首古诗，跳过嵌入（本次启动 0 次 embedding 调用）", storedCount);
            return;
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        collectionAdmin.recreateCollection(embeddings.get(0).dimension());
        embeddingStore.addAll(embeddings, segments);
        log.info("古诗向量化入库完成：{} 首（已写入 Qdrant，重启不再重嵌）", segments.size());
    }
}
