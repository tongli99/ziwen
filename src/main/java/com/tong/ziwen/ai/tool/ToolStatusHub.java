package com.tong.ziwen.ai.tool;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

/**
 * 工具状态推送中枢：维护"会话 id → 当前 SSE 连接"的映射，工具执行层根据会话 id
 * 将状态文案（如"字问先生正在翻古诗书…"）推送到正在等待流式回答的前端页面。
 *
 * <p>SseEmitter 由 controller 持有，工具位于 AiServices 调用链深处，两者通过本类
 * 以会话 id 为中介建立联系。本类放在 tool 包是为了保证依赖方向单向（web 层指向工具层）。
 * 同步问答不登记 emitter，此时推送被静默跳过。</p>
 */
@Slf4j
@Component
public class ToolStatusHub {

    /** 会话 id → 该会话当前流式回答的 SSE 连接。 */
    private final Map<String, SseEmitter> emittersBySession = new ConcurrentHashMap<>();

    /** 流式回答开始时登记 emitter，连接结束（完成、超时）时自动移除。 */
    public void register(String sessionId, SseEmitter emitter) {
        emittersBySession.put(sessionId, emitter);
        // 按 key 加实例匹配移除：同一会话开启新流时会覆盖旧 emitter，旧流结束时不能误删新连接
        emitter.onCompletion(() -> emittersBySession.remove(sessionId, emitter));
        emitter.onTimeout(() -> emittersBySession.remove(sessionId, emitter));
    }

    /**
     * 向该会话的前端页面推送一条具名 status 的 SSE 事件。没有活跃连接时静默跳过。
     */
    public void pushStatus(String sessionId, String statusText) {
        SseEmitter emitter = emittersBySession.get(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("status").data(statusText));
        } catch (IOException | IllegalStateException e) {
            // 推送失败说明连接已断开：移除登记即可，不应让推送异常中断工具执行
            emittersBySession.remove(sessionId, emitter);
            log.debug("状态推送失败（连接可能已断），sessionId={}", sessionId);
        }
    }
}
