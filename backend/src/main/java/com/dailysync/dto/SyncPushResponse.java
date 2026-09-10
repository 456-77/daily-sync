package com.dailysync.dto;

import java.util.List;

/**
 * 批量推送响应。
 *
 * @param vaultVersion 推送后的仓库版本号；整批无实际变更（幂等 no-op）时保持原值不变
 * @param results      逐条结果，与请求条目一一对应
 */
public record SyncPushResponse(long vaultVersion, List<ItemResult> results) {

    /**
     * @param status updated=实际写入（版本推进）；unchanged=内容哈希相同，未产生变更
     */
    public record ItemResult(String path, String status) {
    }
}
