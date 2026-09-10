package com.dailysync.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dailysync.common.BizException;
import com.dailysync.dto.DailyRecordResponse;
import com.dailysync.dto.RecordDateCountResponse;
import com.dailysync.dto.WeeklyRecordResponse;
import com.dailysync.entity.DailyRecord;
import com.dailysync.entity.Vault;
import com.dailysync.mapper.DailyRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日记查询（前端按日浏览用，JWT 保护）。
 * 数据来自同步链路写入的 daily_records：record_date 由文件名首段解析，
 * 周记等无日期的记录不参与按日查询；墓碑一律排除。
 */
@Service
@RequiredArgsConstructor
public class RecordQueryService {

    private final DailyRecordMapper recordMapper;
    private final VaultService vaultService;

    /** 某一天的全部记录（含内容，按路径排序）。一天通常只有几篇，不分页。 */
    public List<DailyRecordResponse> listByDate(Long userId, Long vaultId, LocalDate date) {
        vaultService.ownedVault(userId, vaultId);
        List<DailyRecord> rows = recordMapper.selectList(new QueryWrapper<DailyRecord>()
                .eq("vault_id", vaultId)
                .eq("deleted", 0)
                .eq("record_date", date)
                .orderByAsc("path"));
        return rows.stream()
                .map(r -> new DailyRecordResponse(r.getId(), r.getPath(), r.getContent(),
                        r.getVersion(), r.getUpdatedAt()))
                .toList();
    }

    /**
     * 各日记录数（日历打点）。
     * from/to 缺省为最近 180 天；SQL 端 GROUP BY 聚合，不拉明细。
     */
    public List<RecordDateCountResponse> dateCounts(Long userId, Long vaultId,
                                                    LocalDate from, LocalDate to) {
        Vault vault = vaultService.ownedVault(userId, vaultId);
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(180);
        List<java.util.Map<String, Object>> maps = recordMapper.selectMaps(new QueryWrapper<DailyRecord>()
                .select("record_date AS d", "COUNT(*) AS cnt")
                .eq("vault_id", vault.getId())
                .eq("deleted", 0)
                .isNotNull("record_date")
                .between("record_date", start, end)
                .groupBy("record_date")
                .orderByAsc("record_date"));
        return maps.stream()
                .map(m -> new RecordDateCountResponse(toDate(m.get("d")), ((Number) m.get("cnt")).longValue()))
                .toList();
    }

    /** 周记文件名首段：`2026-W37.md`、`2026-W37 周记.md` 都算，与插件端 getWeeklyKeySet 的正则保持一致 */
    private static final Pattern WEEKLY_PATTERN = Pattern.compile("^(\\d{4}-W\\d{2})(?: |$)");

    /**
     * 周记元数据列表（按周倒序，不含正文）。
     *
     * <p>周记的 record_date 为 NULL，进不了按日查询与日历打点，这里单独按文件名首段识别。
     * 不上溯加列：一个库一年也就几十篇，直接取回后在内存里筛，实测开销可忽略；
     * 将来量大了再考虑像 record_date 那样落一列。
     */
    public List<WeeklyRecordResponse> listWeekly(Long userId, Long vaultId) {
        vaultService.ownedVault(userId, vaultId);
        List<DailyRecord> rows = recordMapper.selectList(new QueryWrapper<DailyRecord>()
                .eq("vault_id", vaultId)
                .eq("deleted", 0));
        return rows.stream()
                .map(r -> new WeeklyRecordResponse(weekOf(r.getPath()), r.getPath(), r.getUpdatedAt()))
                .filter(w -> w.week() != null)
                .sorted(Comparator.comparing(WeeklyRecordResponse::week).reversed())
                .toList();
    }

    /**
     * 按库内路径精确取单个文件全文（周记正文、待办数据文件都靠它）。
     * 走唯一键 (vault_id, path)，是索引查询；不存在或已删除一律 404。
     */
    public DailyRecordResponse getByPath(Long userId, Long vaultId, String path) {
        vaultService.ownedVault(userId, vaultId);
        DailyRecord row = recordMapper.selectOne(new QueryWrapper<DailyRecord>()
                .eq("vault_id", vaultId)
                .eq("path", path)
                .eq("deleted", 0));
        if (row == null) {
            throw new BizException(HttpStatus.NOT_FOUND, "记录不存在");
        }
        return new DailyRecordResponse(row.getId(), row.getPath(), row.getContent(),
                row.getVersion(), row.getUpdatedAt());
    }

    /** 从路径末段文件名解析 ISO 周标识；不是周记命名返回 null */
    private String weekOf(String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1);
        Matcher matcher = WEEKLY_PATTERN.matcher(filename);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** GROUP BY 结果里的日期列可能是 java.sql.Date / LocalDate / String，按实际类型转换 */
    private LocalDate toDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        return LocalDate.parse(value.toString());
    }
}
