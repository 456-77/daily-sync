package com.dailysync.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dailysync.entity.OmofunEntry;
import com.dailysync.entity.OmofunPollRun;
import com.dailysync.entity.OmofunProgressLog;
import com.dailysync.mapper.OmofunEntryMapper;
import com.dailysync.mapper.OmofunPollRunMapper;
import com.dailysync.mapper.OmofunProgressLogMapper;
import com.dailysync.omofun.OmofunProto;
import com.dailysync.omofun.OmofunTime;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * OmoFun 存档的 MySQL 持久层（M11，Rust 版 store.rs 的移植）。
 *
 * <p>核心语义与旧 SQLite 版逐条对齐，改之前先读这里的安全规则：
 * <ul>
 *   <li><b>只增不删</b>：entries 不做物理删除；服务端消失只标 server_gone_at。</li>
 *   <li><b>服务端版本更新优先</b>：拉取时 version 更大才覆盖本地快照；
 *       保活遇到服务端更新只并入、不回写旧进度（避免把用户刚看的进度打回去）。</li>
 *   <li><b>回填只补缺</b>：桌面缓存回填仅在 entries 不存在时插入，绝不覆盖已有记录。</li>
 * </ul>
 *
 * <p>所有时间都是固定长度 UTC ISO 字符串（OmofunTime），字符串比较即时间先后。
 */
@Service
@RequiredArgsConstructor
public class OmofunStoreService {

    private final OmofunEntryMapper entryMapper;
    private final OmofunProgressLogMapper progressLogMapper;
    private final OmofunPollRunMapper runMapper;

    // ---------- 运行记录 ----------

    public long beginRun(String startedAt) {
        OmofunPollRun run = new OmofunPollRun();
        run.setStartedAt(startedAt);
        run.setOk(0);
        runMapper.insert(run);
        return run.getId();
    }

    public void finishRun(long runId, boolean ok, int pages, int serverEntries,
                          long newEntries, long updatedEntries, long backfilled,
                          long resetDetected, String error) {
        OmofunPollRun patch = new OmofunPollRun();
        patch.setId(runId);
        patch.setFinishedAt(OmofunTime.now());
        patch.setOk(ok ? 1 : 0);
        patch.setPages(pages);
        patch.setServerEntries(serverEntries);
        patch.setNewEntries((int) newEntries);
        patch.setUpdatedEntries((int) updatedEntries);
        patch.setBackfilledEntries((int) backfilled);
        patch.setResetDetected((int) resetDetected);
        patch.setError(error);
        runMapper.updateById(patch);
    }

    // ---------- 拉取合并 ----------

    public record MergeResult(long newEntries, long updatedEntries) {
    }

    /** 合并一次成功拉取的服务端结果。返回 (新增, 更新)。 */
    @Transactional
    public MergeResult mergeServerRun(List<OmofunProto.HistoryItem> items, String seenAt) {
        long newEntries = 0;
        long updatedEntries = 0;

        for (OmofunProto.HistoryItem item : items) {
            OmofunProto.ContentRef content = item.content();
            if (content == null) {
                continue;
            }
            String key = content.type() + ":" + content.id();
            String attributesJson = OmofunProto.structToJsonString(item.attributes());
            String snapshotJson = OmofunProto.structToJsonString(item.resourceSnapshot());
            String cover = OmofunProto.coverOf(item.attributes(), item.resourceSnapshot());
            String updatedIso = item.updatedAtIso();

            OmofunEntry existing = entryMapper.selectById(key);
            if (existing == null) {
                OmofunEntry row = new OmofunEntry();
                row.setEntryKey(key);
                row.setContentType(content.type());
                row.setContentId(content.id());
                row.setContentKey(emptyToNull(content.key()));
                row.setTitle(emptyToNull(item.title()));
                row.setCoverUrl(cover);
                row.setLastEpisodeId(emptyToNull(item.lastEpisodeId()));
                row.setLastEpisodeIndex(zeroToNull(item.lastEpisodeIndex()));
                row.setLastLineId(emptyToNull(item.lastLineId()));
                row.setProgressSeconds(zeroToNull(item.progressSeconds()));
                row.setProgressPercent(zeroToNull(item.progressPercent()));
                row.setTotalDurationSeconds(zeroToNull(item.totalDurationSeconds()));
                row.setStatus(zeroToNull(item.status()));
                row.setVersion(item.version());
                row.setServerUpdatedAt(updatedIso);
                row.setAttributesJson(attributesJson);
                row.setResourceSnapshotJson(snapshotJson);
                row.setSource("server");
                row.setFirstSeenAt(seenAt);
                row.setLastSeenAt(seenAt);
                row.setLastServerSeenAt(seenAt);
                row.setServerGoneAt(null);
                row.setOriginallyWatchedAt(updatedIso);
                entryMapper.insert(row);
                insertProgressLog(key, seenAt, "server", item);
                newEntries++;
            } else {
                long localVersion = existing.getVersion() == null ? 0 : existing.getVersion();
                if (item.version() >= localVersion) {
                    LambdaUpdateWrapper<OmofunEntry> update = new LambdaUpdateWrapper<OmofunEntry>()
                            .eq(OmofunEntry::getEntryKey, key)
                            .set(OmofunEntry::getContentKey, emptyToNull(content.key()))
                            .set(OmofunEntry::getTitle, emptyToNull(item.title()))
                            .set(OmofunEntry::getLastEpisodeId, emptyToNull(item.lastEpisodeId()))
                            .set(OmofunEntry::getLastEpisodeIndex, zeroToNull(item.lastEpisodeIndex()))
                            .set(OmofunEntry::getLastLineId, emptyToNull(item.lastLineId()))
                            .set(OmofunEntry::getProgressSeconds, zeroToNull(item.progressSeconds()))
                            .set(OmofunEntry::getProgressPercent, zeroToNull(item.progressPercent()))
                            .set(OmofunEntry::getTotalDurationSeconds, zeroToNull(item.totalDurationSeconds()))
                            .set(OmofunEntry::getStatus, zeroToNull(item.status()))
                            .set(OmofunEntry::getVersion, item.version())
                            .set(OmofunEntry::getSource, "server")
                            .set(OmofunEntry::getLastSeenAt, seenAt)
                            .set(OmofunEntry::getLastServerSeenAt, seenAt)
                            .set(OmofunEntry::getServerGoneAt, null);
                    // COALESCE 语义：服务端这次没带封面/attributes/时间时，保留旧值
                    if (cover != null) {
                        update.set(OmofunEntry::getCoverUrl, cover);
                    }
                    if (attributesJson != null) {
                        update.set(OmofunEntry::getAttributesJson, attributesJson);
                    }
                    if (snapshotJson != null) {
                        update.set(OmofunEntry::getResourceSnapshotJson, snapshotJson);
                    }
                    if (updatedIso != null) {
                        update.set(OmofunEntry::getServerUpdatedAt, updatedIso);
                        update.set(OmofunEntry::getOriginallyWatchedAt, updatedIso);
                    }
                    entryMapper.update(null, update);
                    updatedEntries++;

                    // 进度有变化才写流水（与旧版一致：episode/progress/status/version 任一变化）
                    OmofunProgressLog last = progressLogMapper.selectOne(
                            new QueryWrapper<OmofunProgressLog>()
                                    .eq("entry_key", key).orderByDesc("id").last("LIMIT 1"));
                    boolean changed = last == null
                            || !Objects.equals(last.getEpisodeId(), emptyToNull(item.lastEpisodeId()))
                            || !Objects.equals(last.getProgressSeconds(), item.progressSeconds())
                            || !Objects.equals(last.getStatus(), item.status())
                            || !Objects.equals(last.getVersion(), item.version());
                    if (changed) {
                        insertProgressLog(key, seenAt, "server", item);
                    }
                } else {
                    // 服务端版本较旧（理论上不发生）：只刷新确认时间，不覆盖存档
                    entryMapper.update(null, new LambdaUpdateWrapper<OmofunEntry>()
                            .eq(OmofunEntry::getEntryKey, key)
                            .set(OmofunEntry::getLastSeenAt, seenAt)
                            .set(OmofunEntry::getLastServerSeenAt, seenAt)
                            .set(OmofunEntry::getServerGoneAt, null));
                    updatedEntries++;
                }
            }
        }
        return new MergeResult(newEntries, updatedEntries);
    }

    /** 完整拉取后，把「之前在服务端出现过、本次未出现」的条目标记为已重置。 */
    public long markMissingAsReset(String seenAt) {
        return entryMapper.update(null, new LambdaUpdateWrapper<OmofunEntry>()
                .set(OmofunEntry::getServerGoneAt, seenAt)
                .isNull(OmofunEntry::getServerGoneAt)
                .isNotNull(OmofunEntry::getLastServerSeenAt)
                .lt(OmofunEntry::getLastServerSeenAt, seenAt));
    }

    // ---------- 桌面缓存回填 ----------

    public record BackfillResult(long count, List<String> keys) {
    }

    /**
     * 桌面端本地缓存回填（只补缺）。entries 为 watch-history.json 的 histories 值集合。
     * 服务器版默认 skip-backfill=true，没有这个文件。
     */
    @Transactional
    public BackfillResult backfillDesktopHistory(List<JsonNode> entries, String seenAt) {
        long backfilled = 0;
        List<String> keys = new ArrayList<>();

        for (JsonNode entry : entries) {
            JsonNode content = entry.path("content");
            String type = content.path("type").asText("vod");
            long id = content.path("id").asLong(0);
            String key = type + ":" + id;
            if (entryMapper.selectById(key) != null) {
                continue;
            }

            JsonNode attributes = entry.get("attributes");
            JsonNode resource = entry.get("resourceSnapshot");
            String cover = findCover(attributes, resource);
            String updatedAt = textOrNull(entry.get("updatedAt"));
            String clock = updatedAt == null ? seenAt : updatedAt;

            OmofunEntry row = new OmofunEntry();
            row.setEntryKey(key);
            row.setContentType(type);
            row.setContentId(id);
            row.setContentKey(textOrNull(content.get("key")));
            row.setTitle(textOrNull(entry.get("title")));
            row.setCoverUrl(cover);
            row.setLastEpisodeId(textOrNull(entry.get("lastEpisodeId")));
            row.setLastEpisodeIndex(intOrNull(entry.get("lastEpisodeIndex")));
            row.setLastLineId(textOrNull(entry.get("lastLineId")));
            row.setPlayerName(textOrNull(entry.get("playerName")));
            row.setEpisodeTitle(textOrNull(entry.get("episodeTitle")));
            row.setProgressSeconds(longOrNull(entry.get("progressSeconds")));
            row.setProgressPercent(doubleOrNull(entry.get("progressPercent")));
            row.setTotalDurationSeconds(longOrNull(entry.get("totalDurationSeconds")));
            row.setStatus(intOrNull(entry.get("status")));
            row.setVersion(longOrNull(entry.get("version")));
            row.setServerUpdatedAt(clock);
            row.setAttributesJson(attributes == null ? null : attributes.toString());
            row.setResourceSnapshotJson(resource == null ? null : resource.toString());
            row.setSource("backfill");
            row.setFirstSeenAt(clock);
            row.setLastSeenAt(clock);
            row.setLastServerSeenAt(null);
            row.setServerGoneAt(null);
            row.setOriginallyWatchedAt(updatedAt);
            entryMapper.insert(row);
            backfilled++;
            keys.add(key);

            OmofunProgressLog log = new OmofunProgressLog();
            log.setEntryKey(key);
            log.setSeenAt(seenAt);
            log.setSource("backfill");
            log.setEpisodeId(textOrNull(entry.get("lastEpisodeId")));
            log.setEpisodeIndex(intOrNull(entry.get("lastEpisodeIndex")));
            log.setLineId(textOrNull(entry.get("lastLineId")));
            log.setProgressSeconds(longOrNull(entry.get("progressSeconds")));
            log.setProgressPercent(doubleOrNull(entry.get("progressPercent")));
            log.setTotalDurationSeconds(longOrNull(entry.get("totalDurationSeconds")));
            log.setStatus(intOrNull(entry.get("status")));
            log.setVersion(longOrNull(entry.get("version")));
            log.setServerUpdatedAt(updatedAt);
            progressLogMapper.insert(log);
        }
        return new BackfillResult(backfilled, keys);
    }

    // ---------- 查询 ----------

    public List<OmofunEntry> listAllEntries() {
        return entryMapper.selectList(new QueryWrapper<OmofunEntry>()
                .last("ORDER BY COALESCE(server_updated_at, last_seen_at) DESC"));
    }

    public List<OmofunPollRun> listRuns(int limit) {
        return runMapper.selectList(new QueryWrapper<OmofunPollRun>()
                .orderByDesc("id").last("LIMIT " + limit));
    }

    public List<OmofunProgressLog> listProgressLog(int limit) {
        return progressLogMapper.selectList(new QueryWrapper<OmofunProgressLog>()
                .orderByDesc("id").last("LIMIT " + limit));
    }

    /** 保活候选：范围过滤 + 服务端仍存在 + 空闲超过阈值。 */
    public List<OmofunEntry> keepaliveCandidates(String scope, List<String> whitelist,
                                                 String minIdleIso) {
        List<OmofunEntry> all = listAllEntries();
        List<OmofunEntry> candidates = new ArrayList<>();
        for (OmofunEntry entry : all) {
            boolean scopeOk = switch (scope) {
                case "all" -> true;
                case "whitelist" -> whitelist.contains(entry.getEntryKey());
                default -> Objects.equals(entry.getStatus(), OmofunProto.STATUS_IN_PROGRESS);
            };
            String updated = entry.getServerUpdatedAt() == null ? "" : entry.getServerUpdatedAt();
            if (scopeOk
                    && entry.getLastServerSeenAt() != null
                    && entry.getServerGoneAt() == null
                    && updated.compareTo(minIdleIso) < 0) {
                candidates.add(entry);
            }
        }
        return candidates;
    }

    /** 恢复候选：服务端已重置 或 仅本地回填。 */
    public List<OmofunEntry> restoreCandidates() {
        List<OmofunEntry> candidates = new ArrayList<>();
        for (OmofunEntry entry : listAllEntries()) {
            if (entry.getServerGoneAt() != null || entry.getLastServerSeenAt() == null) {
                candidates.add(entry);
            }
        }
        return candidates;
    }

    // ---------- 写入回执 ----------

    /** 恢复成功后回写条目状态。 */
    public void applyRestored(String key, OmofunProto.HistoryItem item, String seenAt) {
        LambdaUpdateWrapper<OmofunEntry> update = new LambdaUpdateWrapper<OmofunEntry>()
                .eq(OmofunEntry::getEntryKey, key)
                .set(OmofunEntry::getTitle, emptyToNull(item.title()))
                .set(OmofunEntry::getLastEpisodeId, emptyToNull(item.lastEpisodeId()))
                .set(OmofunEntry::getLastEpisodeIndex, zeroToNull(item.lastEpisodeIndex()))
                .set(OmofunEntry::getLastLineId, emptyToNull(item.lastLineId()))
                .set(OmofunEntry::getProgressSeconds, zeroToNull(item.progressSeconds()))
                .set(OmofunEntry::getProgressPercent, zeroToNull(item.progressPercent()))
                .set(OmofunEntry::getTotalDurationSeconds, zeroToNull(item.totalDurationSeconds()))
                .set(OmofunEntry::getStatus, zeroToNull(item.status()))
                .set(OmofunEntry::getVersion, item.version())
                .set(OmofunEntry::getServerUpdatedAt, item.updatedAtIso())
                .set(OmofunEntry::getSource, "server")
                .set(OmofunEntry::getLastSeenAt, seenAt)
                .set(OmofunEntry::getLastServerSeenAt, seenAt)
                .set(OmofunEntry::getServerGoneAt, null);
        String cover = OmofunProto.coverOf(item.attributes(), item.resourceSnapshot());
        if (cover != null) {
            update.set(OmofunEntry::getCoverUrl, cover);
        }
        String attributes = OmofunProto.structToJsonString(item.attributes());
        if (attributes != null) {
            update.set(OmofunEntry::getAttributesJson, attributes);
        }
        String snapshot = OmofunProto.structToJsonString(item.resourceSnapshot());
        if (snapshot != null) {
            update.set(OmofunEntry::getResourceSnapshotJson, snapshot);
        }
        entryMapper.update(null, update);
        insertProgressLog(key, seenAt, "restore", item);
    }

    /** 保活成功后回写服务端返回的最新状态（只更新版本/时间，不动进度快照）。 */
    public void applyKeepalive(String key, OmofunProto.HistoryItem item, String seenAt) {
        entryMapper.update(null, new LambdaUpdateWrapper<OmofunEntry>()
                .eq(OmofunEntry::getEntryKey, key)
                .set(OmofunEntry::getVersion, item.version())
                .set(OmofunEntry::getServerUpdatedAt, item.updatedAtIso())
                .set(OmofunEntry::getLastSeenAt, seenAt)
                .set(OmofunEntry::getLastServerSeenAt, seenAt)
                .set(OmofunEntry::getServerGoneAt, null));
    }

    /** 仅标记「服务端确认可见」（无服务端数据可写时使用）。 */
    public void markServerSeen(String key, String seenAt) {
        entryMapper.update(null, new LambdaUpdateWrapper<OmofunEntry>()
                .eq(OmofunEntry::getEntryKey, key)
                .set(OmofunEntry::getServerGoneAt, null)
                .set(OmofunEntry::getLastServerSeenAt, seenAt)
                .set(OmofunEntry::getLastSeenAt, seenAt));
    }

    // ---------- 内部 ----------

    private void insertProgressLog(String key, String seenAt, String source,
                                   OmofunProto.HistoryItem item) {
        OmofunProgressLog log = new OmofunProgressLog();
        log.setEntryKey(key);
        log.setSeenAt(seenAt);
        log.setSource(source);
        log.setEpisodeId(emptyToNull(item.lastEpisodeId()));
        log.setEpisodeIndex(zeroToNull(item.lastEpisodeIndex()));
        log.setLineId(emptyToNull(item.lastLineId()));
        log.setProgressSeconds(zeroToNull(item.progressSeconds()));
        log.setProgressPercent(zeroToNull(item.progressPercent()));
        log.setTotalDurationSeconds(zeroToNull(item.totalDurationSeconds()));
        log.setStatus(zeroToNull(item.status()));
        log.setVersion(zeroToNull(item.version()));
        log.setServerUpdatedAt(item.updatedAtIso());
        progressLogMapper.insert(log);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Long zeroToNull(long value) {
        return value == 0 ? null : value;
    }

    private static Integer zeroToNull(int value) {
        return value == 0 ? null : value;
    }

    private static Double zeroToNull(double value) {
        return value == 0.0 ? null : value;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static Long longOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asLong();
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asInt();
    }

    private static Double doubleOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asDouble();
    }

    private static String findCover(JsonNode attributes, JsonNode resource) {
        for (JsonNode holder : new JsonNode[]{attributes, resource}) {
            if (holder != null) {
                JsonNode cover = holder.get("cover_url");
                if (cover != null && cover.isTextual()) {
                    return cover.asText();
                }
            }
        }
        return null;
    }
}
