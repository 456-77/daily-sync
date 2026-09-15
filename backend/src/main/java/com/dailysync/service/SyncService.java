package com.dailysync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.common.BizException;
import com.dailysync.common.HashUtil;
import com.dailysync.dto.SyncPullResponse;
import com.dailysync.dto.SyncPushRequest;
import com.dailysync.dto.SyncPushResponse;
import com.dailysync.entity.Attachment;
import com.dailysync.entity.DailyRecord;
import com.dailysync.entity.Vault;
import com.dailysync.mapper.AttachmentMapper;
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

/**
 * 同步核心：批量推送（幂等）与增量拉取。
 *
 * <p>推送两遍处理：先逐条比对现状判定是否需要变更（内容哈希相同即 no-op），
 * 整批都无变更时不递增仓库版本号——网络重试、重复推送都不会惊动其他设备。
 * 拉取以仓库版本号为游标（version &gt; since），删除以墓碑下发。
 */
@Service
@RequiredArgsConstructor
public class SyncService {

    private final VaultMapper vaultMapper;
    private final DailyRecordMapper recordMapper;
    private final AttachmentMapper attachmentMapper;

    /**
     * 批量推送。流程：整批校验 → 逐条判变更 → 有变更才原子递增仓库版本并对变更行落库。
     * 并发推送同一路径由 (vault_id, path) 唯一键兜底，撞上 DuplicateKey 重试即可（内容相同会变 no-op）。
     */
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

    /**
     * 增量拉取：正文与附件共用 {@code version > since} 这一条游标，按 version 升序归并后下发。
     *
     * <p>两张表各取 {@code limit + 1} 条再归并截断到 limit，{@code hasMore} 以归并后的
     * 总数判断。归并无歧义的关键性质：正文一批推送占一个版本、附件一次上传占一个版本，
     * 而版本号是「自增后回读」（InnoDB 行锁保证并发自增互不相同），
     * 所以**同一个版本号下只会出现同一类行**，不会跨表撞车。
     * 归并按 version 升序取，页尾必然是本页最大版本号，客户端沿用既有的
     * {@code since = 页尾 version - 1} 翻页规则即可（重复收到同版本行，按 path 去重）。
     *
     * <p>不加事务：vault 与两张表是三次自动提交读，极端交错下客户端可能重复收到但不会漏收
     * （hasMore=false 时游标取 max(vaultVersion, 本页最大 version) 即安全）。
     */
    public SyncPullResponse pull(Long vaultId, long since, int limit) {
        Vault vault = vaultMapper.selectById(vaultId);
        // 多取一条：靠它判断"还有没有下一页"，不必依赖条数恰好等于 limit
        List<DailyRecord> rows = recordMapper.selectList(Wrappers.<DailyRecord>lambdaQuery()
                .eq(DailyRecord::getVaultId, vaultId)
                .gt(DailyRecord::getVersion, since)
                .orderByAsc(DailyRecord::getVersion)
                .orderByAsc(DailyRecord::getId)
                .last("LIMIT " + (limit + 1)));
        List<Attachment> attachments = attachmentMapper.selectList(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getVaultId, vaultId)
                .gt(Attachment::getVersion, since)
                .orderByAsc(Attachment::getVersion)
                .orderByAsc(Attachment::getId)
                .last("LIMIT " + (limit + 1)));

        List<SyncPullResponse.Record> outRecords = new ArrayList<>();
        List<SyncPullResponse.AttachmentMeta> outAttachments = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (outRecords.size() + outAttachments.size() < limit
                && (i < rows.size() || j < attachments.size())) {
            // 同版本跨表不可能发生，用 <= 取正文保证确定性（真撞上也不会两条都丢）
            boolean takeRecord = j >= attachments.size()
                    || (i < rows.size() && rows.get(i).getVersion() <= attachments.get(j).getVersion());
            if (takeRecord) {
                DailyRecord r = rows.get(i++);
                outRecords.add(new SyncPullResponse.Record(r.getPath(), r.getContent(),
                        r.getDeleted() == 1, r.getVersion(), r.getUpdatedAt()));
            } else {
                Attachment a = attachments.get(j++);
                outAttachments.add(new SyncPullResponse.AttachmentMeta(a.getPath(), a.getName(),
                        a.getSha256(), a.getSize(), a.getDeleted() == 1, a.getVersion()));
            }
        }
        boolean hasMore = rows.size() + attachments.size() > limit;
        return new SyncPullResponse(vault.getVersion(), hasMore, outRecords, outAttachments);
    }

    /** 允许同步的扩展名：库内正文是 .md，插件另外推送的待办数据文件是 .json */
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".md", ".json");

    /** 推送路径校验：相对路径、无反斜杠、无 . / .. / 空段、扩展名在白名单内。 */
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
        if (problem == null && ALLOWED_EXTENSIONS.stream().noneMatch(path::endsWith)) {
            problem = "只同步 .md / .json 文件";
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
