package com.dailysync.dto;

import com.dailysync.entity.OmofunEntry;

/**
 * OmoFun 存档条目响应（M11，网页端直接消费）。
 *
 * <p>字段名与旧 Rust 版 serde 的 camelCase 输出逐一对齐——前端 ui/app.js 未改数据层，
 * 这样迁移只需要换 api-adapter，不必动页面逻辑。json 字段原样带回，导出/报告才有完整数据。
 */
public record OmofunEntryResponse(
        String key,
        String contentType,
        long contentId,
        String contentKey,
        String title,
        String coverUrl,
        String lastEpisodeId,
        Integer lastEpisodeIndex,
        String lastLineId,
        String playerName,
        String episodeTitle,
        Long progressSeconds,
        Double progressPercent,
        Long totalDurationSeconds,
        Integer status,
        Long version,
        String serverUpdatedAt,
        String attributesJson,
        String resourceSnapshotJson,
        String source,
        String firstSeenAt,
        String lastSeenAt,
        String lastServerSeenAt,
        String serverGoneAt,
        String originallyWatchedAt) {

    public static OmofunEntryResponse from(OmofunEntry e) {
        return new OmofunEntryResponse(
                e.getEntryKey(), e.getContentType(), e.getContentId() == null ? 0 : e.getContentId(),
                e.getContentKey(), e.getTitle(), e.getCoverUrl(), e.getLastEpisodeId(),
                e.getLastEpisodeIndex(), e.getLastLineId(), e.getPlayerName(), e.getEpisodeTitle(),
                e.getProgressSeconds(), e.getProgressPercent(), e.getTotalDurationSeconds(),
                e.getStatus(), e.getVersion(), e.getServerUpdatedAt(), e.getAttributesJson(),
                e.getResourceSnapshotJson(), e.getSource(), e.getFirstSeenAt(), e.getLastSeenAt(),
                e.getLastServerSeenAt(), e.getServerGoneAt(), e.getOriginallyWatchedAt());
    }
}
