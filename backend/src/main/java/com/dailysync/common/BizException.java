package com.dailysync.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常：Service 层抛出，由 GlobalExceptionHandler 统一转为
 * 携带对应 HTTP 状态码的 ApiResponse，Controller 无需 try-catch。
 */
@Getter
public class BizException extends RuntimeException {
    private final HttpStatus status;

    public BizException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
