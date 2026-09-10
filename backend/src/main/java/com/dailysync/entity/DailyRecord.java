package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 记录（daily_records 表）：日记文件夹内的一个 .md 文件，同步的最小单元。
 * 业务唯一键 (vaultId, path)；删除不删行而是置墓碑（deleted=1），防止离线设备复活旧内容。
 */
@Data
@TableName("daily_records")
public class DailyRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long vaultId;
    /** 文件在库内的相对路径，如 diary/2026-09-09.md */
    private String path;
    /** 文件全文；墓碑行内容为空串 */
    private String content;
    /** content 的 SHA-256（十六进制），幂等比对依据；墓碑行为空串 */
    private String contentHash;
    /** 1=正常，0=墓碑（删除标记，内容已清空） */
    private Integer deleted;
    /** 写入该行时的仓库版本号；同一批推送产生的所有变更行版本号相同 */
    private Long version;
    /** 从文件名首段解析的记录日期（仅 YYYY-MM-DD 认可，周记等为 null），供前端按日查询 */
    private LocalDate recordDate;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
