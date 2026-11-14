package com.tong.ziwen.ai.common;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 统一返回结构（code/message/data）：code=0 成功，非 0 失败。
 */
@Data
@AllArgsConstructor
public class Response<T> {

    private int code;
    private String message;
    private T data;

    /** 成功应答。 */
    public static <T> Response<T> ok(T data) {
        return new Response<>(0, "ok", data);
    }

    /** 失败应答：message 是给用户看的错误文案，不暴露内部细节。 */
    public static <T> Response<T> error(String message) {
        return new Response<>(1, message, null);
    }
}
