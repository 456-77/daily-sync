package com.dailysync.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 增量拉取响应。
 *
 * @param vaultVersion 仓库当前版本号；hasMore=false 时客户端下次拉取的 since 直接用它
 * @param hasMore      返回条数等于 limit 时为 true（可能还有下一页）；
 *                     true 时下次 since 用最后一条的 version-1，客户端按 path 去重
 * @param records      version 大于 since 的记录（按 version、id 升序），含墓碑
 */
public record SyncPullResponse(long vaultVersion, boolean hasMore, List<Record> records) {

    /**
     * @param deleted true 表示远端已删除（墓碑），客户端应删除本地对应文件
     */
    public record Record(String path, String content, boolean deleted, long version,
                         LocalDateTime updatedAt) {
    }
}
