package com.dailysync.dto;

import jakarta.validation.constraints.Size;

/** 签发同步令牌请求。name 为备注名（如设备名），可空。 */
public record CreateSyncTokenRequest(@Size(max = 64, message = "备注名最长 64 字符") String name) {
}
