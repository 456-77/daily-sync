package com.dailysync.omofun;

import io.grpc.Status;

/**
 * OmoFun 存档链路异常（M11）。
 *
 * <p>带 gRPC 状态码，便于调用方区分版本冲突（ABORTED）与 token 失效（UNAUTHENTICATED）；
 * 非 gRPC 错误（证书、token 文件、DNS）code 为 null。
 */
public class OmofunException extends RuntimeException {

    private final Status.Code code;

    public OmofunException(String message) {
        this(null, message, null);
    }

    public OmofunException(String message, Throwable cause) {
        this(null, message, cause);
    }

    public OmofunException(Status.Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Status.Code getCode() {
        return code;
    }
}
