package com.tong.ziwen.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建娃的请求。
 */
@Data
public class ChildCreateRequest {

    /**
     * 娃的昵称或代号（如"老大""小宝"）。名字长度收到 20 字以内：
     * 这是娃档案唯一的可填信息.
     */
    @NotBlank(message = "昵称不能为空")
    @Size(max = 20, message = "昵称最长 20 个字")
    private String nickname;
}
