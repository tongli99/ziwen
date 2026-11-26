package com.tong.ziwen.common.exception;

/**
 * 未登录或登录态已失效：全局异常处理把它转成 HTTP 401，前端据此回到登录页。
 *
 * <p>不携带信息量：token 校验失败就是失败，不区分"没带 token"和"token 过期"，
 * 避免给探测者反馈哪个 token 曾经过有效。</p>
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException() {
        super("请先登录");
    }
}
