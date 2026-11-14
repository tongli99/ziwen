package com.tong.ziwen.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 同步问答请求。
 */
@Data
public class ChatRequest {

    /** 会话 ID：区分不同用户的对话记忆 */
    @NotBlank(message = "不能为空")
    private String sessionId;

    @NotBlank(message = "不能为空")
    private String question;
}
