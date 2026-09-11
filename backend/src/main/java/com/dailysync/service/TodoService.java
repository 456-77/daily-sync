package com.dailysync.service;

import com.dailysync.common.BizException;
import com.dailysync.dto.SyncPushRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 网页端待办数据的写入。
 *
 * <p>待办在云端就是 {@code daily_records} 里的一条普通记录
 * （path = {@link #TODO_PATH}），所以写入直接复用同步链路：版本号递增、
 * 内容哈希幂等、「整批无变更不推进版本」等语义与插件推送完全一致，
 * 不用另起一套存储。
 *
 * <p><b>服务端不做合并</b>：条目级合并只在插件侧实现一次，避免两处算法
 * 随时间漂移。网页端提交的是「读到的快照 + 本地改动」，插件下次同步会
 * 按条目 id 把两侧合并（网页端多出来的、改过的都会保留）。
 */
@Service
@RequiredArgsConstructor
public class TodoService {

    /** 待办数据在云端的固定路径。与插件 sync.ts 的 TODO_SYNC_PATH、前端 Todos.tsx 保持一致 */
    public static final String TODO_PATH = "daily-sync-todos.json";

    private final SyncService syncService;
    private final VaultService vaultService;
    private final ObjectMapper objectMapper;

    /**
     * 保存网页端提交的待办快照（整份 JSON）。
     *
     * <p>错误：400 内容为空或不是合法 JSON；404 仓库不存在或非本人。
     */
    public void save(Long userId, Long vaultId, String content) {
        vaultService.ownedVault(userId, vaultId);
        if (content == null || content.isBlank()) {
            throw new BizException(HttpStatus.BAD_REQUEST, "待办数据不能为空");
        }
        // 只做最基本的 JSON 校验，挡住明显错误的内容；语义校验交给插件侧合并
        try {
            objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            throw new BizException(HttpStatus.BAD_REQUEST, "待办数据不是合法 JSON");
        }
        syncService.push(vaultId, new SyncPushRequest(
                List.of(new SyncPushRequest.Item(TODO_PATH, content, false))));
    }
}
