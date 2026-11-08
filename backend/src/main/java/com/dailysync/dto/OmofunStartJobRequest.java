package com.dailysync.dto;

import jakarta.validation.constraints.NotBlank;

/** 启动后台任务请求：kind = poll / keepalive / restore，keys 仅 restore 使用。 */
public record OmofunStartJobRequest(
        @NotBlank String kind,
        java.util.List<String> keys) {
}
