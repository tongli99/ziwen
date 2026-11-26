package com.tong.ziwen.ai.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tong.ziwen.common.AuthAttributes;
import com.tong.ziwen.common.Response;
import com.tong.ziwen.ai.dto.ChatWindow;
import com.tong.ziwen.ai.dto.ChildCreateRequest;
import com.tong.ziwen.ai.dto.ChildProfile;
import com.tong.ziwen.ai.service.UserService;
import com.tong.ziwen.ai.tool.ToolCallLimiter;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 娃档案与聊天窗口管理：娃的列出 / 新建 / 删除，窗口的列出 / 新建 / 删除。
 *
 * <p>家长 id 全部取自 {@code @RequestAttribute}——登录校验拦截器放进来的，
 * 与请求参数无关。删娃、删窗口的跨存储清理编排在本类：SQLite 删行、
 * Redis 删记忆。</p>
 */
@RestController
@RequestMapping("/api/children")
@RequiredArgsConstructor
public class ChildController {

    private final UserService userService;
    private final ChatMemoryStore chatMemoryStore;
    private final ToolCallLimiter toolCallLimiter;

    /** 列出当前家长名下的娃，登录后第一件事就是它。 */
    @GetMapping
    public Response<List<ChildProfile>> listChildren(
            @RequestAttribute(AuthAttributes.PARENT_ID) long parentId) {
        return Response.ok(userService.listChildren(parentId));
    }

    /** 新建娃档案：昵称是唯一要填的信息。 */
    @PostMapping
    public Response<ChildProfile> createChild(
            @RequestAttribute(AuthAttributes.PARENT_ID) long parentId,
            @Valid @RequestBody ChildCreateRequest request) {
        return Response.ok(userService.createChild(parentId, request.getNickname()));
    }

    /** 列出娃名下全部窗口：侧栏二级菜单的数据源。 */
    @GetMapping("/{childId}/windows")
    public Response<List<ChatWindow>> listWindows(
            @RequestAttribute(AuthAttributes.PARENT_ID) long parentId,
            @PathVariable long childId) {
        return Response.ok(userService.listWindows(parentId, childId));
    }

    /** 给娃开新窗口：标题先落默认值，首轮问答后服务端换成问题开头。 */
    @PostMapping("/{childId}/windows")
    public Response<ChatWindow> createWindow(
            @RequestAttribute(AuthAttributes.PARENT_ID) long parentId,
            @PathVariable long childId) {
        return Response.ok(userService.createWindow(parentId, childId));
    }

    /**
     * 删除窗口：先校验归属并取回 sessionId，再删窗口行，最后清 Redis 记忆与限次计数。
     * 顺序与删娃同理（见下）。
     */
    @DeleteMapping("/{childId}/windows/{windowId}")
    public Response<Void> deleteWindow(
            @RequestAttribute(AuthAttributes.PARENT_ID) long parentId,
            @PathVariable long childId,
            @PathVariable long windowId) {
        String sessionId = userService.sessionIdOf(parentId, windowId);
        userService.deleteWindow(parentId, childId, windowId);
        chatMemoryStore.deleteMessages(sessionId);
        toolCallLimiter.reset(sessionId);
        return Response.ok(null);
    }

    /**
     * 删除娃：先收集它名下全部窗口的 sessionId（行删了就回溯不了归属链），
     * 再删档案与窗口行，最后逐个清 Redis 记忆与限次计数。
     *
     * <p>顺序是"先校验、SQLite 删成功后才动 Redis"：如果反过来，删档失败时
     * 记忆已经没了，家长只是想删个娃档案却把聊天记录赔了进去。残留方向的
     * 不一致（行删了、记忆还在）最多等到 TTL 自然过期，是更能接受的失败。</p>
     */
    @DeleteMapping("/{childId}")
    public Response<Void> deleteChild(
            @RequestAttribute(AuthAttributes.PARENT_ID) long parentId,
            @PathVariable long childId) {
        List<String> sessionIds = userService.sessionIdsOfChild(parentId, childId);
        userService.deleteChild(parentId, childId);
        for (String sessionId : sessionIds) {
            chatMemoryStore.deleteMessages(sessionId);
            toolCallLimiter.reset(sessionId);
        }
        return Response.ok(null);
    }
}
