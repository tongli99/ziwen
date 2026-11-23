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
 * <p>这一层不做业务，只做四件事：给模型写“说明书”（{@code @Tool}/{@code @P} 的描述——
 * 描述质量直接决定模型调不调、调得对不对）、对模型给的入参做防御、转发检索、
 * 把结果格式化成模型好读的文本。业务留在 PoemService：工具层会随 prompt
 * 调优频繁改，检索逻辑不应该跟着动。</p>
 *
 * <p>入参防御是 v0.3.1 的主角：模型的输出不是代码，是另一个模型的输入——
 * query 可能空、可能长到塞进一整段指令、可能夹带多行文本。工具层是边界，
 * 进门先检查，检索层拿到的永远是“一个干净的短语”。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PoemSearchTool {

    /** 正常检索词不过几十个字，超长说明夹带了别的东西——截断而不是拒收，别让调用失败成为新的分支。 */
    private static final int MAX_QUERY_LENGTH = 50;

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
        long startedAt = System.currentTimeMillis();
        String sanitized = sanitize(query);
        // 空检索词不值得向量化一趟：直接当 0 命中处理，老实回"没查到"，不猜模型想查什么
        List<PoemHit> hits = sanitized.isEmpty() ? List.of() : poemService.search(sanitized);
        // 每次工具调用一行结构化日志：工具名/参数/命中数/耗时——v0.6b 评测数据的地基。
        // 字段竖线分隔而不是塞进自然语句：后续拿正则/脚本扫这行日志就是一条评测记录
        //（sessionId 工具这层还拿不到会话 id，等后面拿得到了再补，先欠着）
        log.info("tool=searchPoems | query=\"{}\" | hits={} | elapsedMs={}",
                sanitized, hits.size(), System.currentTimeMillis() - startedAt);
        if (sanitized.isEmpty()) {
            return "检索词是空的，没查到古诗。";
        }
        if (hits.isEmpty()) {
            // 空结果不能回空串：明确说“没查到”，模型才知道老实拒答而不是硬编。
            // minScore 拦下的低分命中也归到这条路径——“没查到”是真的没有，不是没搜到。
            return "没查到相关的古诗。古诗书里可能没有这首，别拿记忆里的诗顶上。";
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

    /**
     * 入参防御：模型给的 query 不可信——压平换行和空白（多行文本可能是夹带的指令）、
     * 去控制字符、超长截断。留下的只是一个干净的短语。
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String flattened = raw.replaceAll("[\\s\\p{Cntrl}]+", " ").trim();
        return flattened.length() > MAX_QUERY_LENGTH
                ? flattened.substring(0, MAX_QUERY_LENGTH)
                : flattened;
    }

    /** 单条命中格式化为"出处 + 原文 + 相似度"：出处字段齐，模型答完才能自然带上引用。 */
    static String format(PoemHit hit) {
        return "《%s》（%s·%s，%s）\n%s\n（相似度 %s）".formatted(
                hit.title(), hit.dynasty(), hit.author(), hit.lesson(), hit.content(), hit.score());
    }
}
