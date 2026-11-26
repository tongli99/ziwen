package com.tong.ziwen.common.exception;

/**
 * 业务异常：message 就是写给用户看的文案，全局异常处理原样透传。
 *
 * <p>与兜底处理"对外只回通用文案"的分工：能预判的业务错误（重名、密码不对、
 * 找不到这个娃）在抛出点把话说明白；预判不到的异常才落到兜底分支。
 * 因此构造参数必须是给用户看的话，不能塞堆栈、SQL 之类的内部细节。</p>
 */
public class BizException extends RuntimeException {

    /** @param userMessage 给用户看的错误文案。 */
    public BizException(String userMessage) {
        super(userMessage);
    }
}
