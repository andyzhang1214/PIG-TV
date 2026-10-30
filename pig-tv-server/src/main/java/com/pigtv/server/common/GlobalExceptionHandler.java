package com.pigtv.server.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R> handleBiz(BizException e) {
        HttpStatus status = switch (e.getCode()) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage(), e.getData()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R> handleValid(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? "参数不合法" : fe.getField() + " " + fe.getDefaultMessage();
        return ResponseEntity.badRequest().body(R.fail(400, msg));
    }

    /**
     * 静态资源找不到（/files/** 文件被删或地址写错）必须回 404。
     *
     * <p>不单独接住的话会被下面的 Exception 兜底成 500，
     * 排查时看不出「是地址错了」还是「服务挂了」。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R> handleNoResource(NoResourceFoundException e, HttpServletRequest req) {
        log.warn("resource not found：{}", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "文件不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R> handleOther(Exception e, HttpServletRequest req) {
        log.error("unhandled error {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(500, "服务器内部错误"));
    }
}
