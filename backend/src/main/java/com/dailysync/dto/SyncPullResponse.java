package com.dailysync.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 增量拉取响应。
 *
 * @param vaultVersion 仓库当前版本号；hasMore=false 时客户端下次拉取的 since 直接用它
 * @param hasMore      归并后条数超过 limit 时为 true（可能还有下一页）；
 *                     true 时下次 since 用最后一条的 version-1，客户端按 path 去重
 * @param records      version 大于 since 的正文记录（按 version、id 升序），含墓碑
 * @param attachments  version 大于 since 的附件元数据（不含字节，字节另走下载接口）；
 *                     与 records 共用同一条 version 游标，客户端不必维护第二个 cursor
 */
public record SyncPullResponse(long vaultVersion, boolean hasMore, List<Record> records,
                              List<AttachmentMeta> attachments) {

    /**
     * @param deleted true 表示远端已删除（墓碑），客户端应删除本地对应文件
     */
    public record Record(String path, String content, boolean deleted, long version,
                         LocalDateTime updatedAt) {
    }

    /**
     * 附件元数据。客户端据此判断本地该下、该删还是该补传；
     * 真正的字节在需要时才去下载接口取（拉取响应里塞字节会让分页变得没法用）。
     *
     * @param name   文件名（basename），供客户端按 {@code ![[name]]} 反查
     * @param sha256 内容哈希，客户端与本地文件比对以决定是否需要下载
     * @param size   字节数
     * @param deleted true 为墓碑，客户端应删除本地文件
     */
    public record AttachmentMeta(String path, String name, String sha256, long size,
                                 boolean deleted, long version) {
    }
}
