package com.dailysync.service;

import com.dailysync.common.BizException;
import com.dailysync.dto.OmofunMigrateResponse;
import com.dailysync.entity.OmofunEntry;
import com.dailysync.entity.OmofunPollRun;
import com.dailysync.entity.OmofunProgressLog;
import com.dailysync.mapper.OmofunEntryMapper;
import com.dailysync.mapper.OmofunPollRunMapper;
import com.dailysync.mapper.OmofunProgressLogMapper;
import com.dailysync.omofun.OmofunProperties;
import com.dailysync.omofun.OmofunTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * 旧版 SQLite 存档 → MySQL 的一次性迁移（M11）。
 *
 * <p>为什么保留这条通道：entries 是「只增不删」的长期数据，旧版里可能有服务端已经
 * 重置、新服务再也拉不回来的记录。迁移策略是保命的：
 * <ul>
 *   <li>entries：逐条 INSERT IF ABSENT，已存在的不覆盖（新库可能已有更新的快照）；</li>
 *   <li>progress_log / poll_runs：仅在目标表为空时整表导入，避免重复点击造成流水翻倍；</li>
 *   <li>旧 SQLite 文件按配置路径只读打开，不删不改。</li>
 * </ul>
 * 管理员手动触发（POST /api/v1/omofun/migrate-legacy），不随启动自动跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OmofunLegacyMigrationService {

    private final OmofunProperties properties;
    private final OmofunEntryMapper entryMapper;
    private final OmofunProgressLogMapper progressLogMapper;
    private final OmofunPollRunMapper runMapper;

    @Transactional
    public OmofunMigrateResponse migrate() {
        Path sqlitePath = Path.of(properties.getLegacySqlitePath());
        if (!Files.isRegularFile(sqlitePath)) {
            throw new BizException(HttpStatus.BAD_REQUEST,
                    "旧存档不存在: " + sqlitePath.toAbsolutePath());
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "SQLite 驱动未加载");
        }

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + sqlitePath.toAbsolutePath())) {
            long entries = importEntries(conn);
            long progressLog = 0;
            long runs = 0;
            if (progressLogMapper.selectCount(null) == 0) {
                progressLog = importProgressLog(conn);
            }
            if (runMapper.selectCount(null) == 0) {
                runs = importRuns(conn);
            }
            log.info("[omofun] 旧存档迁移完成: entries={}, progressLog={}, runs={}",
                    entries, progressLog, runs);
            return new OmofunMigrateResponse(entries, progressLog, runs);
        } catch (SQLException e) {
            throw new BizException(HttpStatus.BAD_REQUEST,
                    "读取旧 SQLite 存档失败: " + e.getMessage());
        }
    }

    private long importEntries(Connection conn) throws SQLException {
        long imported = 0;
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM entries")) {
            while (rs.next()) {
                String key = rs.getString("key");
                if (key == null || key.isBlank() || entryMapper.selectById(key) != null) {
                    continue;
                }
                OmofunEntry row = new OmofunEntry();
                row.setEntryKey(key);
                row.setContentType(rs.getString("content_type"));
                row.setContentId(longOrNull(rs, "content_id"));
                row.setContentKey(rs.getString("content_key"));
                row.setTitle(rs.getString("title"));
                row.setCoverUrl(rs.getString("cover_url"));
                row.setLastEpisodeId(rs.getString("last_episode_id"));
                row.setLastEpisodeIndex(intOrNull(rs, "last_episode_index"));
                row.setLastLineId(rs.getString("last_line_id"));
                row.setPlayerName(rs.getString("player_name"));
                row.setEpisodeTitle(rs.getString("episode_title"));
                row.setProgressSeconds(longOrNull(rs, "progress_seconds"));
                row.setProgressPercent(doubleOrNull(rs, "progress_percent"));
                row.setTotalDurationSeconds(longOrNull(rs, "total_duration_seconds"));
                row.setStatus(intOrNull(rs, "status"));
                row.setVersion(longOrNull(rs, "version"));
                // 旧库是 chrono/serde 的 RFC3339（可能带纳秒和 +00:00），必须归一化成
                // 固定毫秒 UTC 格式后再落库，否则列宽溢出，且与新数据的字典序比较会错
                row.setServerUpdatedAt(normalizeTime(rs.getString("server_updated_at"), null));
                row.setAttributesJson(rs.getString("attributes_json"));
                row.setResourceSnapshotJson(rs.getString("resource_snapshot_json"));
                String source = rs.getString("source");
                row.setSource(source == null || source.isBlank() ? "server" : source);
                row.setFirstSeenAt(normalizeTime(rs.getString("first_seen_at"), OmofunTime.now()));
                row.setLastSeenAt(normalizeTime(rs.getString("last_seen_at"), OmofunTime.now()));
                row.setLastServerSeenAt(normalizeTime(rs.getString("last_server_seen_at"), null));
                row.setServerGoneAt(normalizeTime(rs.getString("server_gone_at"), null));
                row.setOriginallyWatchedAt(normalizeTime(rs.getString("originally_watched_at"), null));
                entryMapper.insert(row);
                imported++;
            }
        }
        return imported;
    }

    private long importProgressLog(Connection conn) throws SQLException {
        long imported = 0;
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM progress_log ORDER BY id")) {
            while (rs.next()) {
                OmofunProgressLog row = new OmofunProgressLog();
                row.setEntryKey(rs.getString("entry_key"));
                row.setSeenAt(normalizeTime(rs.getString("seen_at"), OmofunTime.now()));
                row.setSource(rs.getString("source"));
                row.setEpisodeId(rs.getString("episode_id"));
                row.setEpisodeIndex(intOrNull(rs, "episode_index"));
                row.setLineId(rs.getString("line_id"));
                row.setProgressSeconds(longOrNull(rs, "progress_seconds"));
                row.setProgressPercent(doubleOrNull(rs, "progress_percent"));
                row.setTotalDurationSeconds(longOrNull(rs, "total_duration_seconds"));
                row.setStatus(intOrNull(rs, "status"));
                row.setVersion(longOrNull(rs, "version"));
                row.setServerUpdatedAt(normalizeTime(rs.getString("server_updated_at"), null));
                progressLogMapper.insert(row);
                imported++;
            }
        }
        return imported;
    }

    private long importRuns(Connection conn) throws SQLException {
        long imported = 0;
        try (Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM poll_runs ORDER BY id")) {
            while (rs.next()) {
                OmofunPollRun row = new OmofunPollRun();
                row.setStartedAt(normalizeTime(rs.getString("started_at"), OmofunTime.now()));
                row.setFinishedAt(normalizeTime(rs.getString("finished_at"), null));
                Integer ok = intOrNull(rs, "ok");
                row.setOk(ok == null ? 0 : ok);
                row.setPages(intOrNull(rs, "pages"));
                row.setServerEntries(intOrNull(rs, "server_entries"));
                row.setNewEntries(intOrNull(rs, "new_entries"));
                row.setUpdatedEntries(intOrNull(rs, "updated_entries"));
                row.setBackfilledEntries(intOrNull(rs, "backfilled_entries"));
                row.setResetDetected(intOrNull(rs, "reset_detected"));
                row.setError(rs.getString("error"));
                runMapper.insert(row);
                imported++;
            }
        }
        return imported;
    }

    /** 旧 RFC3339 / 纯日期 → OmofunTime 固定格式；无法解析时用 fallback。
     *  时间值本身不丢：只把表示形式统一，避免列宽溢出和字典序比较错乱。 */
    private static String normalizeTime(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return OmofunTime.format(OffsetDateTime.parse(raw).toInstant());
        } catch (DateTimeParseException e) {
            try {
                return OmofunTime.format(LocalDate.parse(raw)
                        .atStartOfDay(ZoneOffset.UTC).toInstant());
            } catch (DateTimeParseException ignored) {
                return fallback;
            }
        }
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
