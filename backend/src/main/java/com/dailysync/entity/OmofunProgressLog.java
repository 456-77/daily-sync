package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * OmoFun 进度流水（omofun_progress_log）—— 只增不删的观看轨迹。
 *
 * <p>source 区分来源：server（拉取）、backfill（桌面缓存抢救）、restore（恢复写回）。
 * 字段为 NULL 表示服务端当时没有该值（例如进度 0 不落流水），与旧 SQLite 版语义一致。
 */
@Data
@TableName("omofun_progress_log")
public class OmofunProgressLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entryKey;
    private String seenAt;
    private String source;
    private String episodeId;
    private Integer episodeIndex;
    private String lineId;
    private Long progressSeconds;
    private Double progressPercent;
    private Long totalDurationSeconds;
    private Integer status;
    private Long version;
    private String serverUpdatedAt;
}
