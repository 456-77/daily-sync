package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * OmoFun 存档条目（omofun_entries）。
 *
 * <p>主键是业务 key（{@code 内容类型:内容id}，如 vod:18057），与旧 SQLite 版完全一致，
 * 这样迁移过来的数据无需重新分配 id。时间列都是固定长度 UTC ISO 字符串（VARCHAR），
 * 详见 schema.sql 顶部注释。
 */
@Data
@TableName("omofun_entries")
public class OmofunEntry {

    @TableId(value = "entry_key", type = IdType.INPUT)
    private String entryKey;

    private String contentType;
    private Long contentId;
    private String contentKey;
    private String title;
    private String coverUrl;
    private String lastEpisodeId;
    private Integer lastEpisodeIndex;
    private String lastLineId;
    private String playerName;
    private String episodeTitle;
    private Long progressSeconds;
    private Double progressPercent;
    private Long totalDurationSeconds;
    private Integer status;
    private Long version;
    private String serverUpdatedAt;
    private String attributesJson;
    private String resourceSnapshotJson;
    private String source;
    private String firstSeenAt;
    private String lastSeenAt;
    private String lastServerSeenAt;
    private String serverGoneAt;
    private String originallyWatchedAt;
}
