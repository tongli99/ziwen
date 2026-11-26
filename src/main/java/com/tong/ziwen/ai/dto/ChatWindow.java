package com.tong.ziwen.ai.dto;

/**
 * 聊天窗口（娃名下的子会话）：对内对外的字段就是 id 与标题。
 *
 * <p>同 {@link ChildProfile} 的思路：不携带归属字段（childId/parentId）——
 * 列表接口本就只查"当前家长自己的娃的窗口"，响应里再回传归属字段没有意义。</p>
 */
public record ChatWindow(long id, String title) {
}
