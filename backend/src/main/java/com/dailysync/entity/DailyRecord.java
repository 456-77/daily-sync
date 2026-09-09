package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("daily_records")
public class DailyRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long vaultId;
    private String path;
    private String content;
    private String contentHash;
    private Integer deleted;
    private Long version;
    private LocalDate recordDate;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
