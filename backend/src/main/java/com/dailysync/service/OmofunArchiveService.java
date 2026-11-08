package com.dailysync.service;

import com.dailysync.dto.OmofunKeepaliveConfig;
import com.dailysync.dto.OmofunProbeResponse;
import com.dailysync.entity.OmofunEntry;
import com.dailysync.omofun.OmofunException;
import com.dailysync.omofun.OmofunGrpcClient;
import com.dailysync.omofun.OmofunProperties;
import com.dailysync.omofun.OmofunProto;
import com.dailysync.omofun.OmofunTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 存档 / 保活 / 恢复三大流程（M11，Rust 版 poll.rs 的移植）。
 *
 * <p><b>写操作安全规则（改这里之前必读）</b>：
 * <ul>
 *   <li>只回写存档里的精确快照，绝不杜撰进度；</li>
 *   <li>服务端版本比本地新（用户在别处看过）→ 只并入、不覆盖；</li>
 *   <li>恢复前先 GetHistory：服务端其实还在就只并入存档，不重复新建；</li>
 *   <li>保活走乐观锁（clientVersion=服务端当前 version），ABORTED 视为冲突并重查；</li>
 *   <li>单轮写条数有上限、每次写之间有限速（omofun_settings）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OmofunArchiveService {

    private final OmofunProperties properties;
    private final OmofunTokenService tokenService;
    private final OmofunStoreService store;
    private final OmofunSettingsService settings;
    private final ObjectMapper objectMapper;

    // ---------- 存档 ----------

    public String poll(Consumer<String> log) {
        String seenAt = OmofunTime.now();
        long runId = store.beginRun(seenAt);
        try {
            PollSummary summary = pollInner(seenAt, log);
            store.finishRun(runId, true, summary.pages(), summary.serverEntries(),
                    summary.newEntries(), summary.updatedEntries(), summary.backfilled(),
                    summary.resetDetected(), null);
            return summary.text();
        } catch (RuntimeException e) {
            store.finishRun(runId, false, 0, 0, 0, 0, 0, 0, errorText(e));
            throw e;
        }
    }

    private PollSummary pollInner(String seenAt, Consumer<String> log) {
        String token = tokenService.readAccessToken();
        try (OmofunGrpcClient client = connect()) {
            FetchResult fetched = fetchAll(client, token, log);
            OmofunStoreService.MergeResult merged = store.mergeServerRun(fetched.items(), seenAt);
            long resetDetected = fetched.complete() ? store.markMissingAsReset(seenAt) : 0;

            long backfilled = 0;
            if (!properties.isSkipBackfill()) {
                backfilled = backfillFromDesktopCache(seenAt, log);
            }

            String text = "完成：服务端 " + fetched.items().size() + " 条 / " + fetched.pages()
                    + " 页；新增 " + merged.newEntries() + "，更新 " + merged.updatedEntries()
                    + "，回填 " + backfilled + "，新检出\"服务端已重置\" " + resetDetected + " 条。";
            return new PollSummary(fetched.pages(), fetched.items().size(), merged.newEntries(),
                    merged.updatedEntries(), backfilled, resetDetected, text);
        } catch (OmofunException e) {
            if (OmofunGrpcClient.isUnauthenticated(e)) {
                throw new OmofunException(e.getCode(),
                        "服务端返回 UNAUTHENTICATED：token 已失效。请打开一次 OmoFun Windows 客户端让它刷新登录，然后重试。",
                        e);
            }
            throw e;
        }
    }

    private long backfillFromDesktopCache(String seenAt, Consumer<String> log) {
        String pathText = properties.getDesktopHistoryPath();
        if (pathText == null || pathText.isBlank()) {
            return 0;
        }
        try {
            JsonNode json = objectMapper.readTree(
                    Files.readString(Path.of(pathText), StandardCharsets.UTF_8));
            JsonNode histories = json.path("histories");
            if (!histories.isObject()) {
                return 0;
            }
            List<JsonNode> entries = new ArrayList<>();
            histories.fields().forEachRemaining(e -> entries.add(e.getValue()));
            try {
                OmofunStoreService.BackfillResult result =
                        store.backfillDesktopHistory(entries, seenAt);
                if (!result.keys().isEmpty()) {
                    log.accept("  从桌面客户端本地缓存回填 " + result.count()
                            + " 条服务端已查不到的旧记录");
                }
                return result.count();
            } catch (RuntimeException e) {
                // 回填是尽力而为，个别失败不中断 poll（与旧版一致）
                log.accept("  回填部分失败（已忽略）: " + errorText(e));
                return 0;
            }
        } catch (Exception ignored) {
            // 文件不存在/格式不对：服务器版默认就是没有这个文件
            return 0;
        }
    }

    // ---------- 保活 ----------

    public String keepalive(Consumer<String> log) {
        OmofunKeepaliveConfig keepalive = settings.keepaliveConfig();
        String minIdle = OmofunTime.minusDays(keepalive.minIdleDays());
        List<OmofunEntry> candidates = store.keepaliveCandidates(
                keepalive.scope(), keepalive.whitelist(), minIdle);
        log.accept("[保活] 范围=" + keepalive.scope() + "，空闲>" + keepalive.minIdleDays()
                + "天，候选 " + candidates.size() + " 条（上限 " + keepalive.maxWrites() + "）");
        if (candidates.isEmpty()) {
            return "[保活] 没有待保活条目。";
        }

        String token = tokenService.readAccessToken();
        String seenAt = OmofunTime.now();
        try (OmofunGrpcClient client = connect()) {
            // 一次全量拉取服务端当前状态，避免逐条 GetHistory
            FetchResult fetched = fetchAll(client, token, log);
            Map<String, OmofunProto.HistoryItem> serverMap = new HashMap<>();
            for (OmofunProto.HistoryItem item : fetched.items()) {
                if (item.contentKey() != null) {
                    serverMap.put(item.contentKey(), item);
                }
            }

            long touched = 0;
            long skippedNewer = 0;
            long skippedMissing = 0;
            long failed = 0;

            for (OmofunEntry entry : candidates) {
                if (touched >= keepalive.maxWrites()) {
                    log.accept("  已达单轮上限 " + touched + " 条，剩余留待下一轮");
                    break;
                }
                OmofunProto.HistoryItem serverItem = serverMap.get(entry.getEntryKey());
                if (serverItem == null) {
                    skippedMissing++;
                    continue;
                }
                // 安全规则：服务端版本更新 → 用户在别处看了，先并入存档，不回写旧进度
                long localVersion = entry.getVersion() == null ? 0 : entry.getVersion();
                if (serverItem.version() > localVersion) {
                    store.applyKeepalive(entry.getEntryKey(), serverItem, seenAt);
                    skippedNewer++;
                    continue;
                }

                OmofunProto.ContentRef content = serverItem.content() != null
                        ? serverItem.content()
                        : new OmofunProto.ContentRef(
                        entry.getContentType(),
                        entry.getContentId() == null ? 0 : entry.getContentId(),
                        entry.getContentKey() == null ? "" : entry.getContentKey());
                boolean markCompleted = Objects.equals(
                        entry.getStatus(), OmofunProto.STATUS_COMPLETED);
                OmofunProto.RecordProgressRequest request = new OmofunProto.RecordProgressRequest(
                        content,
                        nullToEmpty(entry.getTitle()),
                        snapshotOf(entry, markCompleted),
                        OmofunProto.jsonStringToStruct(entry.getAttributesJson()),
                        serverItem.version());

                String title = entry.getTitle() == null ? entry.getEntryKey() : entry.getTitle();
                try {
                    OmofunProto.RecordProgressResponse response =
                            client.recordProgress(request, token);
                    touched++;
                    if (response.history() != null) {
                        store.applyKeepalive(entry.getEntryKey(), response.history(), seenAt);
                    }
                    log.accept("  ✔ 续命 [" + touched + "] " + title);
                } catch (OmofunException e) {
                    if (OmofunGrpcClient.isConflict(e)) {
                        // 版本冲突：拉服务端最新并入存档（说明用户在别处看过了）
                        if (serverItem.content() != null) {
                            try {
                                OmofunProto.GetHistoryResponse get = client.getHistory(
                                        new OmofunProto.GetHistoryRequest(serverItem.content(), true),
                                        token);
                                if (get.history() != null) {
                                    store.applyKeepalive(entry.getEntryKey(), get.history(), seenAt);
                                }
                            } catch (OmofunException ignored) {
                                // 冲突后的重查失败不改变结论：本轮跳过，下轮再试
                            }
                        }
                        skippedNewer++;
                        log.accept("  ⏭ 冲突（服务端已更新）" + title);
                    } else {
                        failed++;
                        log.accept("  ✘ 失败 " + title + ": " + e.getMessage());
                    }
                }
                sleep(keepalive.delayMs());
            }

            return "[保活] 完成：续命 " + touched + "，服务端更新已并入 " + skippedNewer
                    + "，服务端已无此条 " + skippedMissing + "，失败 " + failed + "。";
        }
    }

    // ---------- 恢复 ----------

    public String restore(List<String> keys, Consumer<String> log) {
        List<OmofunEntry> all = store.restoreCandidates();
        List<OmofunEntry> candidates = keys == null || keys.isEmpty()
                ? all
                : all.stream().filter(e -> keys.contains(e.getEntryKey())).toList();
        log.accept("[恢复] 候选 " + candidates.size() + " 条");
        if (candidates.isEmpty()) {
            return "[恢复] 没有可恢复条目。";
        }

        String token = tokenService.readAccessToken();
        String seenAt = OmofunTime.now();
        long delayMs = settings.keepaliveConfig().delayMs();
        long restored = 0;
        long already = 0;
        long failed = 0;

        try (OmofunGrpcClient client = connect()) {
            for (OmofunEntry entry : candidates) {
                OmofunProto.ContentRef content = new OmofunProto.ContentRef(
                        entry.getContentType(),
                        entry.getContentId() == null ? 0 : entry.getContentId(),
                        entry.getContentKey() == null ? "" : entry.getContentKey());
                String title = entry.getTitle() == null ? entry.getEntryKey() : entry.getTitle();

                // 先查服务端当前状态
                try {
                    OmofunProto.GetHistoryResponse get = client.getHistory(
                            new OmofunProto.GetHistoryRequest(content, true), token);
                    if (get.history() != null) {
                        store.applyRestored(entry.getEntryKey(), get.history(), seenAt);
                        already++;
                        log.accept("  ⏭ 服务端已存在 " + title + "（已并入存档）");
                        continue;
                    }
                } catch (OmofunException e) {
                    failed++;
                    log.accept("  ✘ 查询失败 " + title + ": " + e.getMessage());
                    continue;
                }

                // 服务端确实没有 → 以 version 0 写回存档中的精确快照
                boolean markCompleted = Objects.equals(
                        entry.getStatus(), OmofunProto.STATUS_COMPLETED);
                OmofunProto.RecordProgressRequest request = new OmofunProto.RecordProgressRequest(
                        content, title, snapshotOf(entry, markCompleted),
                        OmofunProto.jsonStringToStruct(entry.getAttributesJson()), 0);

                try {
                    OmofunProto.RecordProgressResponse response =
                            client.recordProgress(request, token);
                    if (response.history() != null) {
                        store.applyRestored(entry.getEntryKey(), response.history(), seenAt);
                    } else {
                        store.markServerSeen(entry.getEntryKey(), seenAt);
                    }
                    restored++;
                    log.accept("  ✔ 已恢复 [" + restored + "] " + title);
                } catch (OmofunException e) {
                    if (OmofunGrpcClient.isConflict(e)) {
                        // 竞态：刚被并发创建/有墓碑 → 重查一次
                        try {
                            OmofunProto.GetHistoryResponse get = client.getHistory(
                                    new OmofunProto.GetHistoryRequest(content, true), token);
                            if (get.history() != null) {
                                store.applyRestored(entry.getEntryKey(), get.history(), seenAt);
                                already++;
                                log.accept("  ⏭ 冲突后确认服务端已存在 " + title);
                            } else {
                                failed++;
                                log.accept("  ✘ 冲突且无法确认 " + title + ": " + e.getMessage());
                            }
                        } catch (OmofunException retryError) {
                            failed++;
                            log.accept("  ✘ 冲突且无法确认 " + title + ": " + e.getMessage());
                        }
                    } else {
                        failed++;
                        log.accept("  ✘ 失败 " + title + ": " + e.getMessage());
                    }
                }
                sleep(delayMs);
            }
        }

        return "[恢复] 完成：写回 " + restored + "，服务端已存在 " + already + "，失败 " + failed + "。";
    }

    // ---------- 探测 ----------

    /** 传输层自检：只拉 5 条，验证 DNS/TLS/ALPN/签名/token 全链路。 */
    public OmofunProbeResponse probe() {
        String token = tokenService.readAccessToken();
        try (OmofunGrpcClient client = connect()) {
            OmofunProto.ListHistoriesResponse response = client.listHistories(
                    new OmofunProto.ListHistoriesRequest("", 0, 0, 0, 5, true, true), token);
            return new OmofunProbeResponse(response.histories().size(), response.nextCursor());
        }
    }

    // ---------- 内部 ----------

    private OmofunGrpcClient connect() {
        byte[] pinned = OmofunGrpcClient.loadPinnedCertDer(properties.getRootCertPath());
        return OmofunGrpcClient.connect(pinned, properties.getRequestTimeoutMs(),
                properties.getProbeTimeoutMs());
    }

    private FetchResult fetchAll(OmofunGrpcClient client, String token, Consumer<String> log) {
        List<OmofunProto.HistoryItem> items = new ArrayList<>();
        long cursorId = 0;
        long cursorVersion = 0;
        int pages = 0;
        boolean complete = false;

        while (pages < properties.getMaxPages()) {
            OmofunProto.ListHistoriesResponse response = client.listHistories(
                    new OmofunProto.ListHistoriesRequest("", 0, cursorId, cursorVersion,
                            properties.getPageSize(), true, true), token);
            pages++;
            log.accept("  第 " + pages + " 页：" + response.histories().size()
                    + " 条（累计 " + (items.size() + response.histories().size()) + "）");
            for (OmofunProto.HistoryItem item : response.histories()) {
                String key = item.contentKey();
                if (key != null) {
                    items.removeIf(existing -> key.equals(existing.contentKey()));
                }
                items.add(item);
            }
            if (response.nextCursor() == 0) {
                complete = true;
                break;
            }
            cursorId = response.nextCursor();
            cursorVersion = response.nextVersion();
            sleep(properties.getPageDelayMs());
        }
        if (!complete) {
            log.accept("  警告：达到最大页数 " + properties.getMaxPages()
                    + " 仍未拉完（本次跳过重置判定）");
        }
        return new FetchResult(items, pages, complete);
    }

    private static OmofunProto.ProgressSnapshot snapshotOf(OmofunEntry entry,
                                                           boolean markCompleted) {
        return new OmofunProto.ProgressSnapshot(
                nullToEmpty(entry.getLastEpisodeId()),
                entry.getLastEpisodeIndex() == null ? 0 : entry.getLastEpisodeIndex(),
                nullToEmpty(entry.getLastLineId()),
                entry.getProgressSeconds() == null ? 0 : entry.getProgressSeconds(),
                entry.getProgressPercent() == null
                        ? 0f : entry.getProgressPercent().floatValue(),
                entry.getTotalDurationSeconds() == null ? 0 : entry.getTotalDurationSeconds(),
                null,
                markCompleted);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OmofunException("任务被中断");
        }
    }

    private static String errorText(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private record FetchResult(List<OmofunProto.HistoryItem> items, int pages, boolean complete) {
    }

    private record PollSummary(int pages, int serverEntries, long newEntries,
                               long updatedEntries, long backfilled, long resetDetected,
                               String text) {
    }
}
