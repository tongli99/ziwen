package com.tong.ziwen.ai.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.tong.ziwen.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;

/**
 * 单轮工具调用限次器：按会话计数，超出上限的调用不再放行。
 *
 * <p>"轮"指从用户发送一条消息到回答结束的完整过程。工具调用循环只会发生在单轮内，
 * 而会话可以跨轮长期存续，因此计数由 ChatController 在每轮入口清零，
 * 本类限制的是单轮调用量而非会话累计总量。</p>
 */
@Component
@RequiredArgsConstructor
public class ToolCallLimiter {

    private final AiProperties properties;

    /** 会话 id → 本轮已放行的工具调用次数。 */
    private final Map<String, Integer> callsThisTurn = new ConcurrentHashMap<>();

    /** 尝试放行一次：本轮未达上限则计数加一并放行，判断与计数在一次原子操作中完成。 */
    public boolean tryAcquire(String sessionId) {
        int calls = callsThisTurn.merge(sessionId, 1, Integer::sum);
        return calls <= properties.getTool().getMaxCallsPerTurn();
    }

    /** 新一轮对话开始（或会话删除）时清零计数。 */
    public void reset(String sessionId) {
        callsThisTurn.remove(sessionId);
    }
}
