package com.tong.ziwen.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.tong.ziwen.common.Response;

import lombok.extern.slf4j.Slf4j;

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

    /** 业务异常：message 本就是给用户写的文案，原样透传。 */
    @ExceptionHandler(BizException.class)
    public Response<Void> handleBiz(BizException e) {
        return Response.error(e.getMessage());
    }

    /**
     * 未登录 / 登录态失效：HTTP 401 + 统一 body。
     *
     * <p>业务错误保持 200 + code 字段的现状，唯独这一类升到 401——
     * "该重新登录了"是前端要切换页面（回登录页）的信号，值得让
     * fetch 直接从 status 识别，不必解析 body 猜文案。</p>
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Response<Void>> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Response.error(e.getMessage()));
    }

    /** 兜底：日志留全堆栈，对外只回通用文案。 */
    @ExceptionHandler(Exception.class)
    public Response<Void> handleException(Exception e) {
        // 完整堆栈记日志，对外只返回通用文案，避免泄漏内部细节（连接串、key 错误、堆栈等）
        log.error("未处理异常", e);
        return Response.error("系统繁忙，请稍后再试");
    }
}
