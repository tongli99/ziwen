package com.tong.ziwen.ai.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：统一转成 {@link Response} 返回。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数校验失败（@Valid 不通过）：返回第一条字段错误，前端可直接展示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Response<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse("参数校验失败");
        return Response.error(msg);
    }

    /** 兜底：日志留全堆栈，对外只回通用文案。 */
    @ExceptionHandler(Exception.class)
    public Response<Void> handleException(Exception e) {
        // 完整堆栈记日志，对外只返回通用文案，避免泄漏内部细节（连接串、key 错误、堆栈等）
        log.error("未处理异常", e);
        return Response.error("系统繁忙，请稍后再试");
    }
}
