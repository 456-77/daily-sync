package com.dailysync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.common.BizException;
import com.dailysync.common.HashUtil;
import com.dailysync.dto.SyncPullResponse;
import com.dailysync.dto.SyncPushRequest;
import com.dailysync.dto.SyncPushResponse;
import com.dailysync.entity.DailyRecord;
import com.dailysync.entity.Vault;
import com.dailysync.mapper.DailyRecordMapper;
import com.dailysync.mapper.VaultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SyncService {

    private final VaultMapper vaultMapper;
    private final DailyRecordMapper recordMapper;

    @Transactional
    public SyncPushResponse push(Long vaultId, SyncPushRequest req) {
        // 先整体校验再动手：任何一条不合法，整批拒绝，不产生半截变更
        Set<String> seenPaths = new HashSet<>();
        for (SyncPushRequest.Item item : req.items()) {
            validatePath(item.path());
            if (!item.deleted() && item.content() == null) {
                throw new BizException(HttpStatus.BAD_REQUEST, "path: " + item.path() + " 缺少 content");
            }
            if (!seenPaths.add(item.path())) {
                throw new BizException(HttpStatus.BAD_REQUEST, "path: " + item.path() + " 在一次推送中重复出现");
            }
        }

        // 第一遍：读现状，判定每条是否需要变更；同一路径只判一次（上面已拒绝重复）
        int n = req.items().size();
        DailyRecord[] existings = new DailyRecord[n];
        boolean[] changed = new boolean[n];
        for (int i = 0; i < n; i++) {
            SyncPushRequest.Item item = req.items().get(i);
            DailyRecord existing = recordMapper.selectOne(Wrappers.<DailyRecord>lambdaQuery()
                    .eq(DailyRecord::getVaultId, vaultId)
                    .eq(DailyRecord::getPath, item.path()));
            existings[i] = existing;
            if (item.deleted()) {
                changed[i] = existing != null && existing.getDeleted() == 0;
            } else {
                changed[i] = existing == null || existing.getDeleted() == 1
                        || !HashUtil.sha256Hex(item.content()).equals(existing.getContentHash());
            }
        }

        // 幂等核心：整批都是 no-op 时不递增版本号，其他设备不会被无意义唤醒
        Vault vault = vaultMapper.selectById(vaultId);
        long version = vault.getVersion();
        boolean anyChange = false;
        for (boolean b : changed) {
            anyChange |= b;
        }
        if (anyChange) {
            // 原子递增仓库版本号；InnoDB 行锁保证并发推送各自拿到不同的版本号
            vaultMapper.update(null, Wrappers.<Vault>lambdaUpdate()
                    .eq(Vault::getId, vaultId)
                    .setSql("version = version + 1"));
            version = vaultMapper.selectById(vaultId).getVersion();
            for (int i = 0; i < n; i++) {
                if (changed[i]) {
                    applyChange(vaultId, req.items().get(i), existings[i], version);
                }
            }
        }

        List<SyncPushResponse.ItemResult> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(new SyncPushResponse.ItemResult(req.items().get(i).path(),
                    changed[i] ? "updated" : "unchanged"));
        }
        return new SyncPushResponse(version, results);
    }

    /** 落库单条变更（调用前已确认该条需要变更） */
    private void applyChange(Long vaultId, SyncPushRequest.Item item, DailyRecord existing, long version) {
        if (item.deleted()) {
            // 墓碑：内容清空省空间
            existing.setDeleted(1);
            existing.setContent("");
            existing.setContentHash("");
            existing.setVersion(version);
            existing.setRecordDate(null);
            existing.setUpdatedAt(LocalDateTime.now());
            recordMapper.updateById(existing);
            return;
        }
        String hash = HashUtil.sha256Hex(item.content());
        if (existing == null) {
            DailyRecord record = new DailyRecord();
            record.setVaultId(vaultId);
            record.setPath(item.path());
            record.setContent(item.content());
            record.setContentHash(hash);
            record.setDeleted(0);
            record.setVersion(version);
            record.setRecordDate(deriveRecordDate(item.path()));
            record.setUpdatedAt(LocalDateTime.now());
            record.setCreatedAt(LocalDateTime.now());
            recordMapper.insert(record);
        } else {
            existing.setContent(item.content());
            existing.setContentHash(hash);
            existing.setDeleted(0);
            existing.setVersion(version);
            existing.setRecordDate(deriveRecordDate(item.path()));
            existing.setUpdatedAt(LocalDateTime.now());
            recordMapper.updateById(existing);
        }
    }

    public SyncPullResponse pull(Long vaultId, long since, int limit) {
        Vault vault = vaultMapper.selectById(vaultId);
        List<DailyRecord> rows = recordMapper.selectList(Wrappers.<DailyRecord>lambdaQuery()
                .eq(DailyRecord::getVaultId, vaultId)
                .gt(DailyRecord::getVersion, since)
                .orderByAsc(DailyRecord::getVersion)
                .orderByAsc(DailyRecord::getId)
                .last("LIMIT " + limit));
        List<SyncPullResponse.Record> records = rows.stream()
                .map(r -> new SyncPullResponse.Record(r.getPath(), r.getContent(),
                        r.getDeleted() == 1, r.getVersion(), r.getUpdatedAt()))
                .toList();
        return new SyncPullResponse(vault.getVersion(), records.size() == limit, records);
    }

    private void validatePath(String path) {
        String problem = null;
        if (path.startsWith("/") || path.contains("\\")) {
            problem = "必须是库内相对路径";
        } else {
            for (String segment : path.split("/")) {
                if (segment.equals("..") || segment.equals(".") || segment.isEmpty()) {
                    problem = "路径段不能是 . / .. / 空";
                    break;
                }
            }
        }
        if (problem == null && !path.endsWith(".md")) {
            problem = "M2 只同步 .md 文件";
        }
        if (problem != null) {
            throw new BizException(HttpStatus.BAD_REQUEST, "path: " + path + " " + problem);
        }
    }

    /** 从文件名首段解析记录日期（仅 YYYY-MM-DD 认可；周记 2026-W37 等返回 null），供 M4 前端按日查询 */
    private LocalDate deriveRecordDate(String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1);
        // 先剥 .md 后缀：无空格的文件名（2026-09-09.md）首段会带着后缀导致匹配不上
        if (filename.endsWith(".md")) {
            filename = filename.substring(0, filename.length() - 3);
        }
        String firstToken = filename.split(" ")[0];
        if (!firstToken.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.parse(firstToken);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
