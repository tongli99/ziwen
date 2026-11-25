package com.tong.ziwen.ai.tool;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.tong.ziwen.ai.dto.HanziEntry;
import com.tong.ziwen.ai.service.HanziService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 生字表查询工具：把 {@link HanziService} 的两条白名单查询包装成模型可调用的 {@code @Tool}。
 *
 * <p>与 {@link PoemSearchTool} 分工：古诗是语义检索（"写瀑布的诗"要靠向量找"像"），
 * 生字是结构化查询（"灌"哪课学的只有唯一正确答案，精确匹配）。工具不关心数据
 * 存在哪、怎么查——对模型只暴露清晰的函数签名，模型照说明书抽参数即可。</p>
 *
 * <p>入参防御与古诗工具同理：模型给的参数不可信。字参数只取第一个汉字
 * （模型常把"灌"说成"灌字"，取首汉字恰好消化掉这类噪声）；课序越界/抽不出字
 * 时回引导性文本，让模型追问孩子而不是空手硬编。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HanziTool {

    /** 汉字范围用 Unicode 脚本类，不写死码位区间，扩展区汉字也能兜住。 */
    private static final Pattern HAN_CHARACTER = Pattern.compile("\\p{IsHan}");

    private final HanziService hanziService;

    /**
     * 查字→课。描述写给模型看：什么时候该调（问哪课学的、问笔画偏旁）、参数怎么给
     * （单个汉字，不是孩子的原话）、什么时候别调（古诗、词语意思走别的路）。
     */
    @Tool("查生字表：查某个汉字是在哪一课学的。孩子问\"这个字是哪一课学的\""
            + "\"这个字几画、偏旁是什么\"时调用。参数只给单个汉字，如\"灌\"。"
            + "问古诗、问词语意思，不要调用。")
    public String findLessonsByHanzi(@P("要查的单个汉字，如\"灌\"") String hanzi,
                                     @ToolMemoryId String sessionId) {
        long startedAt = System.currentTimeMillis();
        String sanitized = firstHanCharacter(hanzi);
        if (sanitized == null) {
            // 抽参失败的兜底：回引导文本，让模型去问孩子，而不是猜一个字硬查
            log.info("tool=findLessonsByHanzi | sessionId={} | hanzi=null | hits=0 | elapsedMs={}",
                    sessionId, System.currentTimeMillis() - startedAt);
            return "没有拿到要查的汉字。先问清楚孩子想问哪个字，别替他猜。";
        }
        List<HanziEntry> hits = hanziService.findByHanzi(sanitized);
        log.info("tool=findLessonsByHanzi | sessionId={} | hanzi=\"{}\" | hits={} | elapsedMs={}",
                sessionId, sanitized, hits.size(), System.currentTimeMillis() - startedAt);
        if (hits.isEmpty()) {
            // 空结果要说明白是“表里没有”：模型才知道老实说没录到，而不是编一个课序
            return ("生字表里没有「%s」这个字。没查到就是没查到，告诉孩子生字表还没录到，"
                    + "不要猜是哪一课。").formatted(sanitized);
        }
        StringBuilder result = new StringBuilder("「%s」在生字表里出现 %d 次：".formatted(
                sanitized, hits.size()));
        for (HanziEntry hit : hits) {
            result.append("\n- ").append(hit.format());
        }
        return result.toString();
    }

    /**
     * 按课列字。参数只有课序数字，模型把它抽错时（空结果/越界）回范围提示，
     * 引导它跟孩子确认课序而不是报"查无此课"了事。
     */
    @Tool("查生字表：列出某一课的全部生字。孩子要\"第N课的预习字表\"、"
            + "问\"第N课学了哪些字\"时调用。参数给课序数字，如 3 表示第 3 课。")
    public String listCharsByLesson(@P("课序数字，如 3 表示第 3 课") int lessonNo,
                                    @ToolMemoryId String sessionId) {
        long startedAt = System.currentTimeMillis();
        List<HanziEntry> hits = hanziService.findByLesson(lessonNo);
        log.info("tool=listCharsByLesson | sessionId={} | lessonNo={} | hits={} | elapsedMs={}",
                sessionId, lessonNo, hits.size(), System.currentTimeMillis() - startedAt);
        if (hits.isEmpty()) {
            return "第 %d 课没查到生字。%s".formatted(lessonNo, rangeAdvice());
        }
        HanziEntry first = hits.get(0);
        StringBuilder result = new StringBuilder("第 %d 课共 %d 个生字（%s，P%d 起）："
                .formatted(lessonNo, hits.size(), first.edition(), first.pageNo()));
        for (HanziEntry hit : hits) {
            result.append("\n- ").append(hit.hanzi()).append(' ').append(hit.pinyin())
                    .append("（").append(hit.strokeCount()).append("画，")
                    .append(hit.radical()).append("部）");
        }
        return result.toString();
    }

    /**
     * 入参防御：模型给的字符串可能空、可能带"字"、可能整句孩子的原话——
     * 取其中第一个汉字。取不到（null、空白、纯拼音）返回 null，交给调用方兜底。
     */
    static String firstHanCharacter(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = HAN_CHARACTER.matcher(raw);
        return matcher.find() ? matcher.group() : null;
    }

    /** 课序越界的兜底文案：带上已录入范围，比单说“没查到”更能引导模型追问孩子。 */
    private String rangeAdvice() {
        return hanziService.lessonRange()
                .map(range -> ("生字表里目前录的是第 %d~%d 课，可能课序记错了，也可能还没录到，"
                        + "跟孩子确认一下。").formatted(range[0], range[1]))
                .orElse("生字表还是空的，先跟孩子确认课序。");
    }
}
