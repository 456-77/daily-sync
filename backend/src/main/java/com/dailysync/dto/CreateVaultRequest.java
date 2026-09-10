package com.dailysync.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建仓库请求。 */
public record CreateVaultRequest(
        @NotBlank @Size(max = 64, message = "仓库名长度需要 1-64") String name) {
}
