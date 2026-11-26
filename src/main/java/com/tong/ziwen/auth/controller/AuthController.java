package com.tong.ziwen.auth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tong.ziwen.auth.AuthInterceptor;
import com.tong.ziwen.auth.model.AuthRequest;
import com.tong.ziwen.auth.service.AccountService;
import com.tong.ziwen.auth.service.TokenService;
import com.tong.ziwen.common.Response;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 家长注册 / 登录 / 注销。
 *
 * <p>注册即登录：注册成功直接签发 token（省一次"注册完跳登录页再敲一遍密码"）。
 * token 放在响应 data 里，由前端存到 localStorage，此后每个请求以
 * {@code Authorization: Bearer} 头带回（解析在 {@link AuthInterceptor}）；
 * token 侧的会话管理（Redis 滑动过期、注销即失效）在 {@link TokenService}。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AccountService accountService;
    private final TokenService tokenService;

    /**
     * 注册：用户名重复、密码校验失败等业务错误由全局异常处理转成可展示文案。
     */
    @PostMapping("/register")
    public Response<String> register(@Valid @RequestBody AuthRequest request) {
        return Response.ok(tokenService.issueToken(accountService.register(
                request.getUsername(), request.getPassword()).id()));
    }

    /**
     * 登录：账号密码核对通过即签发 token，token 同时写入 Redis。
     */
    @PostMapping("/login")
    public Response<String> login(@Valid @RequestBody AuthRequest request) {
        long parentId = accountService.login(request.getUsername(), request.getPassword());
        return Response.ok(tokenService.issueToken(parentId));
    }

    /**
     * 注销：Redis 里删 token（立即失效），前端随后自行清掉本地存的 token。
     * 未登录状态下调用也返回成功——注销幂等，无需先有登录态。
     */
    @PostMapping("/logout")
    public Response<Void> logout(HttpServletRequest servletRequest) {
        String token = AuthInterceptor.bearerToken(servletRequest);
        if (token != null) {
            tokenService.revoke(token);
        }
        return Response.ok(null);
    }
}