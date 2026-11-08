package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * OmoFun 存档的单行运行配置（omofun_settings，id 恒为 1）。
 *
 * <p>只放网页端能改的东西：保活范围/阈值/上限/限速、调度开关与时间、上次触发日期。
 * token、证书、数据目录属于部署环境，走 application.yml / env，不进数据库。
 */
@Data
@TableName("omofun_settings")
public class OmofunSetting {

    @TableId(type = IdType.INPUT)
    private Integer id;

    private String keepaliveScope;
    private Integer keepaliveIntervalDays;
    private Integer keepaliveMinIdleDays;
    private Integer keepaliveMaxWrites;
    private Long keepaliveDelayMs;
    /** JSON 数组字符串，如 ["vod:18057"]；空/损坏按空数组处理 */
    private String keepaliveWhitelist;

    private Integer scheduleEnabled;
    private String pollTime;
    private Integer keepaliveEveryDays;
    private String keepaliveTime;

    private String lastPollDate;
    private String lastKeepaliveDate;
    private LocalDateTime updatedAt;
}
