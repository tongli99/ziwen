package com.tong.ziwen.ai.controller;

import com.tong.ziwen.ai.common.Response;
import com.tong.ziwen.ai.dto.ChatRequest;
import com.tong.ziwen.ai.service.Companion;
import com.tong.ziwen.ai.tool.ToolCallLimiter;
import com.tong.ziwen.ai.tool.ToolStatusHub;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.validation.Valid;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话接口：同步问答 + SSE 流式问答 + 删除会话记忆。
 *
 * <p>v0.3.2 起附加两项工作：每轮入口重置工具限次器计数（限制范围是单轮）；
 * 流式开始时将 emitter 登记到 {@link ToolStatusHub}，使工具状态能够推送到前端页面。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final Companion companion;
    private final ChatMemoryStore chatMemoryStore;
    private final ToolCallLimiter toolCallLimiter;
    private final ToolStatusHub toolStatusHub;

    /**
     * 同步问答：一次性返回完整回答。
     */
    @PostMapping
    public Response<String> chat(@Valid @RequestBody ChatRequest request) {
        toolCallLimiter.reset(request.getSessionId());
        return Response.ok(companion.chat(request.getSessionId(), request.getQuestion()));
    }

    /**
     * SSE 流式问答：逐 token 推送（默认事件），工具状态走具名 status 事件。
     * GET + EventSource，方便前端直连。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String sessionId, @RequestParam String question) {
        SseEmitter emitter = new SseEmitter(120_000L);
        toolCallLimiter.reset(sessionId);
        toolStatusHub.register(sessionId, emitter);
        companion.stream(sessionId, question)
                .onPartialResponse(token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                })
                .onCompleteResponse(response -> emitter.complete())
                .onError(e -> {
                    log.error("流式对话失败, sessionId={}", sessionId, e);
                    emitter.completeWithError(e);
                })
                .start();
        return emitter;
    }

    /**
     * 删除整个会话的记忆：前端"删除会话"时同步清除 Redis 中的记忆，
     * 并重置限次计数，避免残留条目占用内存。
     */
    @DeleteMapping("/{sessionId}")
    public Response<Void> clearMemory(@PathVariable String sessionId) {
        chatMemoryStore.deleteMessages(sessionId);
        toolCallLimiter.reset(sessionId);
        return Response.ok(null);
    }
}
