package com.tong.ziwen.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tong.ziwen.ai.config.AiProperties;
import com.tong.ziwen.ai.service.PoemService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

/**
 * 古诗检索工具测试：验证的是"给模型看的输出"——格式、出处、空结果话术，
 * 全程不依赖真实模型（embedding 用假实现，向量库用内存实现）。
 */
class PoemSearchToolTest {

    /**
     * 假 embedding 模型，两维就够：文本含"瀑布"嵌成 [1,0]，其余 [0,1]。
     * PoemServiceTest 那台是三维的（多一支"语料里没有的诗"，好测 minScore 阈值），
     * 这里只断言"问瀑布命中瀑布诗"，用不上第三维。
     */
    private final EmbeddingModel waterfallFakeModel = new EmbeddingModel() {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            return Response.from(segments.stream()
                    .map(segment -> Embedding.from(
                            segment.text().contains("瀑布") ? new float[] {1, 0} : new float[] {0, 1}))
                    .toList());
        }
    };

    @Test
    void toolResultCarriesPoemWithCitationForTheModel() {
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        PoemService poemService = new PoemService(
                new ObjectMapper(), waterfallFakeModel, store, new AiProperties());
        List<TextSegment> segments = poemService.toSegments();
        store.addAll(waterfallFakeModel.embedAll(segments).content(), segments);
        PoemSearchTool tool = new PoemSearchTool(poemService);

        String result = tool.searchPoems("二年级那首写瀑布的诗");

        // 模型拿到的是"出处 + 原文 + 相似度"，信息够它答完自然带上引用
        assertThat(result)
                .contains("望庐山瀑布")
                .contains("李白")
                .contains("二年级上")
                .contains("飞流直下三千尺")
                .contains("相似度");
    }

    @Test
    void toolReturnsHonestMessageWhenStoreIsEmpty() {
        // 空库时不能返回空串：明确告诉模型"没查到"，它才知道老实说而不是硬编
        PoemService emptyStoreService = new PoemService(
                new ObjectMapper(), waterfallFakeModel, new InMemoryEmbeddingStore<>(), new AiProperties());
        PoemSearchTool tool = new PoemSearchTool(emptyStoreService);

        assertThat(tool.searchPoems("写瀑布的诗")).contains("没查到");
    }

    @Test
    void blankOrBlankishQueryGetsNotFoundInsteadOfARetrieval() {
        // 空白检索词不值得向量化一趟：老实回"没查到"，不猜模型想查什么。
        // 假模型被调即抛异常——只有短路真的发生（没走向量化）测试才绿；
        // 若退化成"空库检索也回没查到"，两条路径同一个输出，断言就白写了
        EmbeddingModel neverCalledModel = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                throw new AssertionError("空白检索词不该走到向量化");
            }
        };
        PoemService poemService = new PoemService(
                new ObjectMapper(), neverCalledModel, new InMemoryEmbeddingStore<>(), new AiProperties());
        PoemSearchTool tool = new PoemSearchTool(poemService);

        assertThat(tool.searchPoems("  ")).contains("没查到");
    }

    @Test
    void sanitizeDefendsAgainstUntrustedToolInput() {
        // 模型给的入参不是代码变量，是另一次生成的输出：空、多行夹带、超长都要拦
        assertThat(PoemSearchTool.sanitize(null)).isEmpty();
        assertThat(PoemSearchTool.sanitize("   ")).isEmpty();
        // 多行文本（可能是夹带的指令）压平成单个短语
        assertThat(PoemSearchTool.sanitize("李白\n\n望庐山瀑布")).isEqualTo("李白 望庐山瀑布");
        // 超长截断：正常检索词不过几十字，长到超限说明塞了别的东西
        assertThat(PoemSearchTool.sanitize("瀑布".repeat(60))).hasSize(50);
    }
}
