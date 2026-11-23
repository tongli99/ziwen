package com.tong.ziwen.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tong.ziwen.ai.config.AiProperties;
import com.tong.ziwen.ai.dto.Poem;
import com.tong.ziwen.ai.dto.PoemHit;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

/**
 * 古诗服务测试：语料完整性、条目构造、裸检索排序——全程不依赖真实模型。
 */
class PoemServiceTest {

    /**
     * 假 embedding 模型：文本含"瀑布"嵌成 [1,0,0]、含"梅花"（语料里没有）嵌成 [0,0,1]，
     * 其余 [0,1,0]——三支互为正交。这样既能离线断言"问瀑布命中瀑布诗"，
     * 又能让"问语料里没有的诗"全体停在 0.5 分，被 minScore 拦出空结果。
     */
    private final EmbeddingModel waterfallFakeModel = new EmbeddingModel() {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream()
                    .map(segment -> Embedding.from(vectorOf(segment.text())))
                    .toList());
        }

        private float[] vectorOf(String text) {
            if (text.contains("瀑布")) {
                return new float[] {1, 0, 0};
            }
            if (text.contains("梅花")) {
                return new float[] {0, 0, 1};
            }
            return new float[] {0, 1, 0};
        }
    };

    private final EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

    private final AiProperties properties = new AiProperties();

    private PoemService poemService;

    @BeforeEach
    void setUp() {
        poemService = new PoemService(new ObjectMapper(), waterfallFakeModel, embeddingStore, properties);
    }

    @Test
    void corpusHasAtLeastFiveCompletePoems() {
        assertThat(poemService.all()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(poemService.all())
                .allSatisfy(poem -> {
                    assertThat(poem.title()).isNotBlank();
                    assertThat(poem.author()).isNotBlank();
                    assertThat(poem.dynasty()).isNotBlank();
                    assertThat(poem.content()).isNotBlank();
                    // 课序按"X年级X·第N课"录入，检索命中后直接当出处回显
                    assertThat(poem.lesson()).matches(".+年级.+·第\\d+课");
                });
        assertThat(poemService.all())
                .extracting(Poem::title)
                .doesNotHaveDuplicates();
    }

    @Test
    void corpusContainsTheWaterfallPoemForAcceptance() {
        // 验收锚点："问二年级写瀑布的诗能命中《望庐山瀑布》"的前提是它在库里
        Optional<Poem> waterfall = poemService.all().stream()
                .filter(poem -> poem.title().equals("望庐山瀑布"))
                .findFirst();
        assertThat(waterfall).isPresent();
        assertThat(waterfall.get().lesson()).startsWith("二年级上");
    }

    @Test
    void segmentCarriesCitationInTextSoGradeIsSearchable() {
        Optional<TextSegment> waterfallSegment = poemService.toSegments().stream()
                .filter(segment -> segment.metadata().getString("title").equals("望庐山瀑布"))
                .findFirst();
        assertThat(waterfallSegment).isPresent();

        TextSegment waterfall = waterfallSegment.get();
        // "二年级"只存在于课序里：不写进 text，向量检索永远搜不到"二年级那首"
        assertThat(waterfall.text())
                .contains("望庐山瀑布")
                .contains("李白")
                .contains("二年级上")
                .contains("飞流直下三千尺");
        // 出处走结构化 metadata 回显，避免从文本里反向解析
        assertThat(waterfall.metadata().getString("author")).isEqualTo("李白");
        assertThat(waterfall.metadata().getString("dynasty")).isEqualTo("唐");
        assertThat(waterfall.metadata().getString("lesson")).isEqualTo("二年级上·第8课");
    }

    @Test
    void searchRanksTheWaterfallPoemFirst() {
        // 手动替 PoemIngestor 走一遍开机入库（假模型嵌入），再验证裸检索排序
        List<TextSegment> segments = poemService.toSegments();
        embeddingStore.addAll(waterfallFakeModel.embedAll(segments).content(), segments);

        List<PoemHit> hits = poemService.search("二年级那首写瀑布的诗");

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).title()).isEqualTo("望庐山瀑布");
        // 假模型下问题向量与《望庐山瀑布》条目同向，余弦相关度应为满分
        assertThat(hits.get(0).score()).isGreaterThan(0.99);
    }

    @Test
    void minScoreFiltersOutTheLookalikePoemsSoEmptyMeansNotFound() {
        List<TextSegment> segments = poemService.toSegments();
        embeddingStore.addAll(waterfallFakeModel.embedAll(segments).content(), segments);

        // v0.3 的老问题：不设阈值，问"写瀑布的诗"连不相干的诗都挤进前三
        //（InMemoryEmbeddingStore 把余弦换算到 [0,1]：正交的诗也有 0.5 分——正好扮演真机里 0.74 的"最像的错诗"）
        properties.getRag().setMinScore(0.0);
        assertThat(poemService.search("写瀑布的诗")).hasSize(3);

        // v0.3.1：阈值拦下低分命中，剩下的才叫"查到了"
        properties.getRag().setMinScore(0.6);
        List<PoemHit> hits = poemService.search("写瀑布的诗");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).title()).isEqualTo("望庐山瀑布");

        // 反过来验证：问一首语料里没有的诗（"梅花"不在库里），假模型下所有条目都停在 0.5 分，
        // 同一道阈值一拦就空——空结果才真正意味着"没查到"，拒答才有依据
        assertThat(poemService.search("写梅花的诗")).isEmpty();
    }
}
