package com.tong.ziwen.ai.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tong.ziwen.ai.config.AiProperties;
import com.tong.ziwen.ai.dto.Poem;
import com.tong.ziwen.ai.dto.PoemHit;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * 古诗服务：读古诗库、造检索条目、裸检索，v0.2 古诗 RAG 的业务都汇在这里。
 *
 * <p>语料是人工对照课本录入的事实数据（data/poems.json），不从网络爬取；
 * 教程读者请换成自家娃课本的篇目——结构照抄，内容自己敲。</p>
 */
@Service
public class PoemService {

    private static final String POEMS_RESOURCE = "data/poems.json";

    private final List<Poem> poems;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final AiProperties properties;

    public PoemService(ObjectMapper objectMapper,
                       EmbeddingModel embeddingModel,
                       EmbeddingStore<TextSegment> embeddingStore,
                       AiProperties properties) {
        this.poems = loadPoems(objectMapper);
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.properties = properties;
    }

    /** 全部古诗条目（按录入顺序）。 */
    public List<Poem> all() {
        return poems;
    }

    /**
     * 整库转检索条目：一首诗一个 chunk。
     *
     * <p>一首绝句拢共二十八个字，整首才凑得齐完整语义，拆成单句反而会
     * "命中了一句、丢了题目和出处"，所以这里按"整首"为最小检索单位。</p>
     */
    public List<TextSegment> toSegments() {
        return poems.stream()
                .map(poem -> TextSegment.from(segmentText(poem), metadataOf(poem)))
                .toList();
    }

    /**
     * 裸检索：问题向量化后去向量库捞最相近的几首诗，不调 chat 模型。
     *
     * <p>“最相近的几首”是陷阱：向量检索永远返回最近的 K 个，哪怕库里根本没有
     * 那首诗，也会照常回“最像的错诗”。所以这里用 {@code minScore} 把低分命中
     * 拦在门外——被拦干净了，空结果才真正意味着“没查到”，拒答才有依据。</p>
     *
     * <p>排查 RAG 问题的第一站——“没答对”先看这里检索回了什么、得分多少，
     * 是检索没命中，还是生成出了问题。</p>
     */
    public List<PoemHit> search(String question) {
        Embedding queryEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(properties.getRag().getMaxResults())
                .minScore(properties.getRag().getMinScore())
                .build());
        return result.matches().stream()
                .map(PoemHit::from)
                .toList();
    }

    /**
     * 条目文本：《题目》（朝代·作者，课序）+ 原文。
     *
     * <p>课序（{@code poem.lesson()}）存的是"二年级上·第8课"，这几个字不在诗的原文里——
     * 向量化只算 text，所以课序也要拼进 {@code segmentText()}，不然"二年级那首"这种问法就匹配不到。</p>
     */
    static String segmentText(Poem poem) {
        return "《%s》（%s·%s，%s）%n%s".formatted(
                poem.title(), poem.dynasty(), poem.author(), poem.lesson(), poem.content());
    }

    /** 出处同时存一份结构化 metadata：检索命中后原样回显，不用再从文本里反向解析。 */
    private static Metadata metadataOf(Poem poem) {
        return Metadata.from(Map.of(
                "title", poem.title(),
                "author", poem.author(),
                "dynasty", poem.dynasty(),
                "lesson", poem.lesson(),
                "content", poem.content()));
    }

    /** 语料加载失败或不足 5 首直接拒绝启动：语料是 RAG 的地基，坏库则系统不运行。 */
    private static List<Poem> loadPoems(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(POEMS_RESOURCE).getInputStream()) {
            List<Poem> loaded = objectMapper.readValue(in, new TypeReference<List<Poem>>() { });
            if (loaded.size() < 5) {
                throw new IllegalStateException("古诗库至少要有 5 首：" + POEMS_RESOURCE);
            }
            return List.copyOf(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("古诗库加载失败：" + POEMS_RESOURCE, e);
        }
    }
}
