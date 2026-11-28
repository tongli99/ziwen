package com.tong.ziwen.ai.service;

import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 编排层：每轮问答先分诊再分发——背诗交给背诗专才，查字交给查字专才，
 * 其余（以及一切分不准的）落回全工具的全能伴读兜底。
 *
 * <p>sessionId 在这里只透传不改写：三个分身共享同一窗口记忆，分诊分掉的是
 * 工具和说明书，不是记忆。每轮比单分身多出一次分诊调用，这是多分身编排
 * 的固定开销；对外接口与请求参数完全不变。</p>
 *
 * <p>分诊出岔子分三种，各有一层拦：模型拿不准的，说明书判据归 CHAT；分诊调用本身抛异常的
 * （接口超时、限流、回答对不上枚举），这里 catch 住返回 CHAT——两种都落到全工具伴读。
 * 模型自信地分错的那一种拦不住，它会交给错的专才，由专才说明书"老实说、教孩子换个问法"接住。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatDispatcher {

    private final RouterAgent routerAgent;
    private final PoemTutor poemTutor;
    private final HanziTutor hanziTutor;
    private final Companion companion;

    /** 同步问答：先分诊，按意图交给对应分身，返回其完整回答。 */
    public String chat(String sessionId, String question) {
        return switch (classifyOrFallback(sessionId, question)) {
            case POEM -> poemTutor.chat(sessionId, question);
            case HANZI -> hanziTutor.chat(sessionId, question);
            case CHAT -> companion.chat(sessionId, question);
        };
    }

    /** 流式问答：分发规则与同步版完全一致，返回对应分身的流。 */
    public TokenStream stream(String sessionId, String question) {
        return switch (classifyOrFallback(sessionId, question)) {
            case POEM -> poemTutor.stream(sessionId, question);
            case HANZI -> hanziTutor.stream(sessionId, question);
            case CHAT -> companion.stream(sessionId, question);
        };
    }

    /**
     * 分诊，同时接住分诊自身的失败：分诊调用抛异常（接口超时、限流、输出解析失败等）时
     * 返回闲聊意图——全工具伴读接得住任何话，分诊台挂掉只损失专才的收益，
     * 不放大成整轮问答失败。失败日志带会话 id，格式与其他结构化日志一致，
     * 便于按会话排障。
     */
    private Intent classifyOrFallback(String sessionId, String question) {
        try {
            Intent intent = routerAgent.classify(question);
            log.info("router=classify | sessionId={} | question=\"{}\" | intent={}",
                    sessionId, question, intent);
            return intent;
        } catch (RuntimeException e) {
            log.warn("router=classify | sessionId={} | question=\"{}\" | fallback=CHAT | error={}",
                    sessionId, question, e.getMessage());
            return Intent.CHAT;
        }
    }
}
