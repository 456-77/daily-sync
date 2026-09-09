package com.dailysync.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SyncPushRequest(
        @NotEmpty @Size(max = 200, message = "单次最多推送 200 条") List<@Valid Item> items) {

    public record Item(
            @NotBlank @Size(max = 255) String path,
            @Size(max = 1_048_576, message = "单条内容超过 1MB 上限") String content,
            boolean deleted) {
    }
}
