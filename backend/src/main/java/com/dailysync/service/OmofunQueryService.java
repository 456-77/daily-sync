package com.dailysync.service;

import com.dailysync.dto.OmofunEntryResponse;
import com.dailysync.dto.OmofunOverviewResponse;
import com.dailysync.dto.OmofunPollRunResponse;
import com.dailysync.entity.OmofunEntry;
import com.dailysync.omofun.OmofunProto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * OmoFun 存档只读查询（M11）：总览统计、全部条目、恢复候选。
 *
 * <p>统计口径与旧 Rust 版逐条对齐：server_gone_at 非空或从未在服务端见过 → 仅存档；
 * 否则按 status 分「在看/看完/暂停」。列表顺序沿用 store 的
 * COALESCE(server_updated_at, last_seen_at) DESC。
 */
@Service
@RequiredArgsConstructor
public class OmofunQueryService {

    private final OmofunStoreService store;
    private final OmofunTokenService tokenService;

    public OmofunOverviewResponse overview() {
        List<OmofunEntry> entries = store.listAllEntries();
        int watching = 0;
        int done = 0;
        int paused = 0;
        int archived = 0;
        for (OmofunEntry entry : entries) {
            boolean gone = entry.getServerGoneAt() != null || entry.getLastServerSeenAt() == null;
            if (gone) {
                archived++;
            } else if (Objects.equals(entry.getStatus(), OmofunProto.STATUS_COMPLETED)) {
                done++;
            } else if (Objects.equals(entry.getStatus(), OmofunProto.STATUS_PAUSED)) {
                paused++;
            } else {
                watching++;
            }
        }

        List<OmofunEntryResponse> recent = new ArrayList<>();
        for (OmofunEntry entry : entries) {
            if (entry.getServerGoneAt() == null
                    && entry.getLastServerSeenAt() != null
                    && !Objects.equals(entry.getStatus(), OmofunProto.STATUS_COMPLETED)
                    && !Objects.equals(entry.getStatus(), OmofunProto.STATUS_PAUSED)) {
                recent.add(OmofunEntryResponse.from(entry));
                if (recent.size() >= 14) {
                    break;
                }
            }
        }

        List<OmofunPollRunResponse> runs = store.listRuns(8).stream()
                .map(OmofunPollRunResponse::from)
                .toList();

        return new OmofunOverviewResponse(
                tokenService.decodeAccountUidSafe(),
                entries.size(), watching, done, paused, archived, recent, runs);
    }

    public List<OmofunEntryResponse> entries() {
        return store.listAllEntries().stream().map(OmofunEntryResponse::from).toList();
    }

    public List<OmofunEntryResponse> restoreCandidates() {
        return store.restoreCandidates().stream().map(OmofunEntryResponse::from).toList();
    }
}
