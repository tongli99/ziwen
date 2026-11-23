package com.tong.ziwen.ai.tool;

import java.util.List;

import org.springframework.stereotype.Component;

import com.tong.ziwen.ai.dto.PoemHit;
import com.tong.ziwen.ai.service.PoemService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 古诗检索工具：把 {@link PoemService#search} 包装成模型可调用的 {@code @Tool}。
 *
 * <p>这一层不做业务，只做三件事：给模型写"说明书"（{@code @Tool}/{@code @P} 的描述——
 * 描述质量直接决定模型调不调、调得对不对）、转发检索、把结果格式化成模型好读的文本。
 * 业务留在 PoemService：工具层会随 prompt 调优频繁改，检索逻辑不应该跟着动。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PoemSearchTool {

    private final PoemService poemService;

    /**
     * 查古诗书。描述是写给模型看的说明书：什么时候该调（背不全、问出处、问作者、问课序）、
     * 参数怎么给（适合检索的短语，而不是孩子的原话）、什么时候别调（闲聊、生字、词语）。
     * 少了"什么时候别调"，模型会把每句话都拿去查一遍。
     */
    @Tool("在古诗书里检索古诗。孩子问到具体某首诗时调用：背不全想确认原文、问哪首诗写了什么、"
            + "问作者是谁、问是几年级哪一课学的。参数给适合检索的短语，如\"写瀑布的诗\"\"李白 望庐山瀑布\"。"
            + "闲聊、问生字怎么写、问词语意思，不要调用。")
    public String searchPoems(@P("检索古诗书用的关键词或问题，如\"写瀑布的诗\"") String query) {
        List<PoemHit> hits = poemService.search(query);
        log.info("查古诗工具被调用：query=\"{}\"，命中 {} 首", query, hits.size());
        if (hits.isEmpty()) {
            return "没查到相关的古诗。";
        }
        StringBuilder result = new StringBuilder();
        for (PoemHit hit : hits) {
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(format(hit));
        }
        return result.toString();
    }

    /** 单条命中格式化为"出处 + 原文 + 相似度"：出处字段齐，模型答完才能自然带上引用。 */
    static String format(PoemHit hit) {
        return "《%s》（%s·%s，%s）\n%s\n（相似度 %s）".formatted(
                hit.title(), hit.dynasty(), hit.author(), hit.lesson(), hit.content(), hit.score());
    }
}
