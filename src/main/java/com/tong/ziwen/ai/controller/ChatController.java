package com.tong.ziwen.ai.controller;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tong.ziwen.common.AuthAttributes;
import com.tong.ziwen.common.Response;
import com.tong.ziwen.ai.dto.ChatRequest;
import com.tong.ziwen.ai.service.ChatDispatcher;
import com.tong.ziwen.ai.service.UserService;
import com.tong.ziwen.ai.tool.ToolCallLimiter;
import com.tong.ziwen.ai.tool.ToolStatusHub;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 对话接口：同步问答 + SSE 流式问答 + 清空某个窗口的记忆。
 *
 * <p>v0.5 起 sessionId 仍是那个 sessionId，只是内容从单段字符串升级为
 * {@code parentId:childId:windowId} 三段：家长 id 来自登录态（{@code @RequestAttribute}），
 * 窗口 id 来自请求参数，经 窗口→娃→家长 归属链校验后拼键——前端传什么参数
 * 都冒充不了别家的记忆。同一娃的多个窗口各是一条独立记忆，互不串门。</p>
 *
 * <p>v0.3.2 起附加两项工作不变：每轮入口重置工具限次器计数（限制范围是单轮）；
 * 流式开始时将 emitter 登记到 {@link ToolStatusHub}，使工具状态能够推送到前端页面。</p>
 *
 * <p>v0.5.1起不再直接调全能伴读：问答先过 ChatDispatcher 分诊，背诗、查字
 * 各交各的专才，其余（含分不准的）落回全能伴读兜底。对外接口与请求参数不变。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatDispatcher chatDispatcher;
    private final UserService userService;
    private final ChatMemoryStore chatMemoryStore;
    private final ToolCallLimiter toolCallLimiter;
    private final ToolStatusHub toolStatusHub;

    /**
     * 同步问答：一次性返回完整回答。窗口标题若还是默认值，先换成问题开头
     * （首问自动命名，侧栏里多个窗口才分得清，下同）。
     */
    @PostMapping
    public Response<String> chat(
            @RequestAttribute(AuthAttributes.PARENT_ID) long parentId,
            @Valid @RequestBody ChatRequest request) {
        String sessionId = userService.sessionIdOf(parentId, request.getWindowId());
        userService.retitleIfDefault(parentId, request.getWindowId(), request.getQuestion());
        toolCallLimiter.reset(sessionId);
        return Response.ok(chatDispatcher.chat(sessionId, request.getQuestion()));
    }

    /**
     * SSE 流式问答：逐 token 推送（默认事件），工具状态走具名 status 事件。
     * GET 请求，问句放查询参数；前端用 fetch 读流连接——登录态走
     * Authorization 头，EventSource 发不了自定义头，用不上。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestAttribute(AuthAttributes.PARENT_ID) long parentId,
            @RequestParam long windowId,
            @RequestParam String question) {
        String sessionId = userService.sessionIdOf(parentId, windowId);
        userService.retitleIfDefault(parentId, windowId, question);
        SseEmitter emitter = new SseEmitter(120_000L);
        toolCallLimiter.reset(sessionId);
        toolStatusHub.register(sessionId, emitter);
        chatDispatcher.stream(sessionId, question)
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
     * 清空某个窗口的记忆（家长侧"清空聊天"）：窗口与娃档案都不动，只清 Redis
     * 里的对话记忆，并重置限次计数。归属校验先行，别家的记忆清不着。
     */
    @DeleteMapping("/memory/{windowId}")
    public Response<Void> clearMemory(
            @RequestAttribute(AuthAttributes.PARENT_ID) long parentId,
            @PathVariable long windowId) {
        String sessionId = userService.sessionIdOf(parentId, windowId);
        chatMemoryStore.deleteMessages(sessionId);
        toolCallLimiter.reset(sessionId);
        return Response.ok(null);
    }
}
