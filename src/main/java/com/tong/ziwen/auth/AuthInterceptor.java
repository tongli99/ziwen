package com.tong.ziwen.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.tong.ziwen.auth.service.TokenService;
import com.tong.ziwen.common.AuthAttributes;
import com.tong.ziwen.common.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 登录校验拦截器：挡在 /api/** 前面（放行项见 {@link WebAuthConfig}），
 * 校验 Authorization 头里的 Bearer token，把"当前是哪个家长"放进 request
 * attribute，下游 controller 用 {@code @RequestAttribute} 取用。
 *
 * <p>下游因此拿不到任何来自请求参数的身份：家长 id 只出自登录态，
 * 前端传参伪造不了别的家长——身份可信是串号防线的前半段，后半段
 * （娃归属校验）在 UserService。</p>
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** Authorization 头的 Bearer 方案前缀：{@code Authorization: Bearer <token>}。 */
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = bearerToken(request);
        Long parentId = token == null ? null : tokenService.parentIdOf(token);
        if (parentId == null) {
            throw new UnauthorizedException();
        }
        request.setAttribute(AuthAttributes.PARENT_ID, parentId);
        return true;
    }

    /**
     * 从 Authorization 头解析 Bearer token；头缺失、方案不符或 token 为空返回 null。
     * 静态方法：注销等放行入口（不走拦截器）也要读 token，共用这一处解析。
     */
    public static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}