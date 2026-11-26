package com.tong.ziwen.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 家长注册 / 登录请求（两个场景字段相同，共用一个载体）。
 */
@Data
public class AuthRequest {

    /**
     * 家长用户名，全库唯一。长度 2~32：下限挡单字符占名，上限挡超长灌库
     */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 32, message = "用户名长度需在 2~32 个字符")
    private String username;

    /**
     * 明文密码：只存在于请求途中，落库前即被 BCrypt 单向哈希。
     * 长度 6~64：下限挡"一位密码"注册——哈希再强也架不住穷举，长度才是密码学。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 个字符")
    private String password;
}