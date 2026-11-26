package com.tong.ziwen.common;

/**
 * 登录态在请求作用域传递的属性名契约：auth 模块的拦截器校验通过后写入，
 * 业务模块的 controller 以 {@code @RequestAttribute} 读取。
 * 属性名是 auth 与业务模块共同的约定，定义处必须中立——放 common，
 * 两个模块才能互不依赖。
 */
public final class AuthAttributes {

    /** 当前登录家长的 id，由 AuthInterceptor 从 token 解出后写入。 */
    public static final String PARENT_ID = "ziwen.parentId";

    private AuthAttributes() {
    }
}
