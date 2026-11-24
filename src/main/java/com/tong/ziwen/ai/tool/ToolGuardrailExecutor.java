package com.tong.ziwen.ai.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具护栏执行器：实现 ToolExecutor 接口，作为真实工具执行的代理，在每次调用前依次完成
 * 三项工作：调用次数校验（超限时返回 {@link #STOP_ADVICE}，不再放行）、向会话推送状态、
 * 委托被包装的执行器执行。
 *
 * <p>设计说明：
 * <ul>
 * <li>超限时返回文本而不抛出异常：工具返回值会回传给大模型继续生成，返回引导性文本
 * 可以让模型基于已有结果正常结束本轮对话；抛出异常则会中断整轮请求。</li>
 * <li>本类不注册为 Spring bean：状态文案因工具而异，每个工具各有一条</li>
 * </ul></p>
 */
@Slf4j
@RequiredArgsConstructor
public class ToolGuardrailExecutor implements ToolExecutor {

    /** 劝停文本：超限时返回给模型的指令文本，用于引导模型停止调用工具并直接作答。 */
    static final String STOP_ADVICE =
            "这一轮查询的次数已经到上限，不要再调用工具了。根据已经查到的结果直接回答；"
                    + "确实查不到的，老实说没查到，别编。";

    private final ToolExecutor delegate;
    private final String workingStatusText;
    private final ToolCallLimiter toolCallLimiter;
    private final ToolStatusHub toolStatusHub;

    /**
     * 调用次数校验、推送状态、委托执行。
     *
     * @param request  模型发起的工具调用请求（工具名 + 参数 JSON）
     * @param memoryId 会话 id，由 AiServices 从 @MemoryId 参数传入
     */
    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String sessionId = String.valueOf(memoryId);
        if (!toolCallLimiter.tryAcquire(sessionId)) {
            // 与放行时的结构化日志保持同一格式，额外增加 blocked 字段
            log.warn("tool={} | sessionId={} | arguments={} | blocked=true",
                    request.name(), sessionId, request.arguments());
            return STOP_ADVICE;
        }
        // 调用期间向前端推送状态文案；不推送"查询完成"，因为首个 token 到达后前端会自行清除状态行
        toolStatusHub.pushStatus(sessionId, workingStatusText);
        return delegate.execute(request, memoryId);
    }
}
