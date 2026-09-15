package com.dailysync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件（attachments 表）：日记引用到的图片 / PDF，同步的最小单元之一。
 *
 * <p>表里<b>只存元数据</b>，字节不进数据库——按内容寻址落在磁盘上
 * （{@code <storage.dir>/<vaultId>/<sha256 前两位>/<sha256>}，见
 * {@link com.dailysync.service.AttachmentStore}），两边靠 sha256 关联。
 *
 * <p>与 daily_records 同构：{@code (vaultId, path)} 唯一，删除置墓碑（deleted=1）
 * 而不是删行，防止离线设备把旧内容复活。version 取自同一个 vaults.version
 * 计数器，因此正文与附件共用一条拉取游标。
 */
@Data
@TableName("attachments")
public class Attachment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long vaultId;
    /** 附件在库内的相对路径，如 attachments/Pasted image 20260915.png */
    private String path;
    /**
     * path 的文件名部分（basename）。
     * 日记里 {@code ![[截图.png]]} 这种写法不带目录，Obsidian 按文件名在全库找，
     * 网页端因此只能给文件名——单列出来加索引，免得每次同步都全表 LIKE。
     */
    private String name;
    /** 内容 SHA-256（十六进制）：磁盘 blob 的文件名，也是幂等比对依据；墓碑行为空串 */
    private String sha256;
    /** 字节数，配额累加用；墓碑行清 0 */
    private Long size;
    /** 1=正常，0=墓碑。墓碑行不删磁盘 blob：同仓库其他路径可能仍在引用同一份内容 */
    private Integer deleted;
    /** 写入该行时的仓库版本号 */
    private Long version;
    private LocalDateTime updatedAt;
    private LocalDateTime createdAt;
}
