package com.dailysync.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dailysync.dto.DailyRecordResponse;
import com.dailysync.dto.RecordDateCountResponse;
import com.dailysync.entity.DailyRecord;
import com.dailysync.entity.Vault;
import com.dailysync.mapper.DailyRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

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
