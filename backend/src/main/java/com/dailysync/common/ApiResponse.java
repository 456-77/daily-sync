package com.dailysync.common;

/**
 * 全部接口的统一响应包装：{@code code=0} 表示成功，非 0 为错误码（与 HTTP 状态码一致），
 * message 为可展示的错误描述，data 为业务数据（出错时为 null）。
 */
public record ApiResponse<T>(int code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static ApiResponse<Void> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
