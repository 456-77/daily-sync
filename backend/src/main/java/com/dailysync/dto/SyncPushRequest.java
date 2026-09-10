package com.dailysync.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量推送请求。任何一条不合法则整批拒绝（400），不产生半截变更。
 *
 * @param items 待推送条目，≤200 条；同一路径只能出现一次（重复 400）
 */
public record SyncPushRequest(
        @NotEmpty @Size(max = 200, message = "单次最多推送 200 条") List<@Valid Item> items) {

    /**
     * @param path    库内相对路径，.md 结尾，无反斜杠 / . / .. 段
     * @param content 文件全文，≤1MB（按字符数）；deleted=true 时可省略
     * @param deleted true 表示删除该路径（墓碑）；对同一路径重推内容即复活
     */
    public record Item(
            @NotBlank @Size(max = 255) String path,
            @Size(max = 1_048_576, message = "单条内容超过 1MB 上限") String content,
            boolean deleted) {
    }
}
