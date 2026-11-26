package com.tong.ziwen.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 同步问答请求。
 */
@Data
public class ChatRequest {

    /**
     * 聊的是哪个窗口（窗口 id）。sessionId 的窗口段：服务端由它沿
     * 窗口→娃→家长 校验归属后拼成完整 sessionId，前端只报窗口、不报家长，
     * 家长身份伪造不了。
     */
    @NotNull(message = "不能为空")
    private Long windowId;

    @NotBlank(message = "不能为空")
    private String question;
}
