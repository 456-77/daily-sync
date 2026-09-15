package com.dailysync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.common.BizException;
import com.dailysync.dto.AttachmentDeleteResponse;
import com.dailysync.dto.AttachmentUploadResponse;
import com.dailysync.entity.Attachment;
import com.dailysync.mapper.AttachmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 附件：日记引用到的图片 / PDF。
 *
 * <p>字节落磁盘（{@link AttachmentStore} 按仓库分层的内容寻址），数据库只存元数据。
 * version 取自同一个 {@code vaults.version} 计数器，所以正文与附件共用一条拉取游标，
 * 客户端不需要维护第二个 cursor。
 *
 * <p>幂等语义与 {@link SyncService#push} 保持一致：整条变更无实际变化时
 * <b>不推进版本号</b>，网络重试、重复上传都不会惊动其他设备。
 *
 * <p>逐条上传/删除<b>不写审计日志</b>：与正文推送一致（push 也不逐条审计），
 * 否则每次粘贴几十张图就会把安全日志刷成流水账。仓库级事件（VAULT_CREATE）
 * 与用户级事件照常审计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    /**
     * 允许的附件类型白名单。
     * 刻意不含 svg：svg 能内联脚本，浏览器直出等于给了一条 XSS 通道。
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "pdf");

    /** 扩展名 -> Content-Type。服务端自己映射，不采信客户端传来的类型。 */
    private static final Map<String, String> MIME_BY_EXT = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "pdf", "application/pdf");

    /** path 列宽 VARCHAR(512)；name 列宽 VARCHAR(255) */
    private static final int PATH_MAX = 512;
    private static final int NAME_MAX = 255;

    private final AttachmentMapper attachmentMapper;
    private final VaultService vaultService;
    private final AttachmentStore blobStore;

    /** 单文件上限（默认 10MB），超限 413 */
    @Value("${daily-sync.attachments.max-file-bytes:10485760}")
    private long maxFileBytes;

    /** 每仓库附件配额（默认 1GB），超限 413 */
    @Value("${daily-sync.attachments.quota-bytes:1073741824}")
    private long quotaBytes;

    /**
     * 上传/覆盖一个附件。
     *
     * <p>边读边算哈希并落盘，读取上限取「单文件上限」与「仓库剩余配额」的较小者——
     * 一次流式读取就把两个限制都卡住，既不先把大文件写完再回滚，也不会因为粗粒度
     * 预检把「覆盖成更小的文件」误判成超配额。
     *
     * @throws BizException 400 路径/类型不合法；413 超单文件上限或仓库配额
     */
    @Transactional
    public AttachmentUploadResponse store(Long vaultId, String path, InputStream in) {
        String cleanPath = validatePath(path);
        Attachment existing = selectByPath(vaultId, cleanPath);
        // 覆盖同一路径时旧占用的字节会释放，配额按净增量算
        long previous = existing != null && existing.getDeleted() == 0 ? existing.getSize() : 0L;

        long headroom = quotaBytes - attachmentMapper.sumSizeByVault(vaultId) + previous;
        if (headroom <= 0) {
            throw quotaExceeded();
        }
        long limit = Math.min(maxFileBytes, headroom);
        boolean quotaLimited = limit < maxFileBytes;

        AttachmentStore.Stored stored;
        try {
            stored = blobStore.write(vaultId, in, limit);
        } catch (AttachmentStore.TooLargeException e) {
            // 上限是被配额压下来的，就得报配额而不是报单文件上限，否则提示会误导
            throw quotaLimited ? quotaExceeded() : new BizException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "附件超过单文件上限 " + (maxFileBytes / 1024 / 1024) + "MB");
        } catch (IOException e) {
            log.error("附件写入失败 vaultId={} path={}", vaultId, cleanPath, e);
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "附件写入失败");
        }

        // 幂等核心：同路径同内容同大小 = 无变更，不推进版本号（重试上传不会唤醒其他设备）
        if (existing != null && existing.getDeleted() == 0
                && stored.sha256().equals(existing.getSha256()) && existing.getSize() == stored.size()) {
            return new AttachmentUploadResponse(cleanPath, stored.sha256(), stored.size(),
                    vaultService.currentVersion(vaultId), "unchanged");
        }

        long version = vaultService.bumpVersion(vaultId);
        upsert(vaultId, cleanPath, stored, version);
        return new AttachmentUploadResponse(cleanPath, stored.sha256(), stored.size(), version, "stored");
    }

    /**
     * 置墓碑删除。对未知/已删除的路径是无操作（不推进版本号），
     * 与 push 对未知路径的处理一致，方便客户端无脑重推。
     */
    @Transactional
    public AttachmentDeleteResponse tombstone(Long vaultId, String path) {
        String cleanPath = validatePath(path);
        Attachment existing = selectByPath(vaultId, cleanPath);
        if (existing == null || existing.getDeleted() == 1) {
            return new AttachmentDeleteResponse(cleanPath, vaultService.currentVersion(vaultId), "unchanged");
        }
        long version = vaultService.bumpVersion(vaultId);
        existing.setDeleted(1);
        // 墓碑行清空内容信息省空间；磁盘 blob 保留——同仓库其他路径可能仍引用同一份内容
        existing.setSha256("");
        existing.setSize(0L);
        existing.setVersion(version);
        existing.setUpdatedAt(LocalDateTime.now());
        attachmentMapper.updateById(existing);
        return new AttachmentDeleteResponse(cleanPath, version, "deleted");
    }

    /**
     * 定位并打开一个附件供下载。
     *
     * <p>寻址二选一：{@code path} 精确定位（插件用），或 {@code name} 按文件名解析
     * （网页端渲染 {@code ![[截图.png]]} 用，这种写法不带目录）。
     *
     * @param from 引用方的记录路径，仅 {@code name} 寻址时用于「同目录优先」判定
     * @throws BizException 400 两个参数都没给；404 附件不存在或磁盘文件缺失
     */
    public Blob open(Long vaultId, String path, String name, String from) {
        Attachment row = null;
        if (path != null && !path.isBlank()) {
            String clean = path.trim();
            row = selectByPath(vaultId, clean);
            if (row == null || row.getDeleted() == 1) {
                // 精确路径没命中就按文件名再找一次：网页端会把 ![](attachments/a.png)
                // 这类相对路径按「相对当前笔记」解析后传过来，而库里同一张图也可能被写成
                // 「相对库根」——两种约定都兜住，总比让图裂开好（Obsidian 本身就是多规则兜底）
                row = resolveByName(vaultId, clean, clean);
            }
        } else if (name != null && !name.isBlank()) {
            row = resolveByName(vaultId, name.trim(), from);
        } else {
            throw new BizException(HttpStatus.BAD_REQUEST, "缺少参数: path 或 name");
        }
        if (row == null || row.getDeleted() == 1) {
            throw new BizException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        Path blob = blobStore.blobPath(vaultId, row.getSha256());
        if (!Files.isRegularFile(blob)) {
            // 元数据在、盘上文件没了（磁盘被清过 / 换了挂载点）——明确 404，
            // 别返回空响应让前端只能显示裂图
            log.warn("附件元数据存在但磁盘文件缺失 vaultId={} path={} sha256={}", vaultId, row.getPath(), row.getSha256());
            throw new BizException(HttpStatus.NOT_FOUND, "附件文件缺失");
        }
        return new Blob(row.getPath(), row.getName(), row.getSha256(), row.getSize(),
                mimeOf(row.getName()), blob);
    }

    /**
     * 按文件名解析（{@code ![[截图.png]]} 不带目录的写法）。
     * 规则与 Obsidian 一致：先看引用方所在目录，再退化成全库最短路径
     * （同长度取字典序）。确定性很重要——同一份内容每次都要解析到同一个 path，
     * 否则不同设备、不同次渲染可能取到不同的同名文件。
     */
    private Attachment resolveByName(Long vaultId, String name, String from) {
        // name 里万一带了目录，只取文件名部分（前端应当直接传 basename）
        String base = name.substring(name.lastIndexOf('/') + 1);
        if (base.length() > NAME_MAX) {
            return null;
        }
        if (from != null && !from.isBlank()) {
            int slash = from.lastIndexOf('/');
            String candidate = slash < 0 ? base : from.substring(0, slash + 1) + base;
            Attachment sameDir = selectByPath(vaultId, candidate);
            if (sameDir != null && sameDir.getDeleted() == 0) {
                return sameDir;
            }
        }
        return attachmentMapper.selectList(Wrappers.<Attachment>lambdaQuery()
                        .eq(Attachment::getVaultId, vaultId)
                        .eq(Attachment::getName, base)
                        .eq(Attachment::getDeleted, 0)
                        // CHAR_LENGTH 而不是 LENGTH：前者按字符数，字符串更短者优先（与直觉一致）
                        .last("ORDER BY CHAR_LENGTH(path) ASC, path ASC LIMIT 1")).stream()
                .findFirst()
                .orElse(null);
    }

    private Attachment selectByPath(Long vaultId, String path) {
        return attachmentMapper.selectOne(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getVaultId, vaultId)
                .eq(Attachment::getPath, path));
    }

    /**
     * 写入或更新元数据行。先试插入、撞唯一键再改更新——
     * 与 {@link VaultService#findOrCreateVault} 同样的并发兜底：
     * (vault_id, path) 唯一键是幂等 upsert 的前提。
     */
    private void upsert(Long vaultId, String path, AttachmentStore.Stored stored, long version) {
        Attachment existing = selectByPath(vaultId, path);
        if (existing == null) {
            Attachment row = new Attachment();
            row.setVaultId(vaultId);
            row.setPath(path);
            row.setName(basenameOf(path));
            row.setSha256(stored.sha256());
            row.setSize(stored.size());
            row.setDeleted(0);
            row.setVersion(version);
            row.setUpdatedAt(LocalDateTime.now());
            row.setCreatedAt(LocalDateTime.now());
            try {
                attachmentMapper.insert(row);
                return;
            } catch (DuplicateKeyException e) {
                existing = selectByPath(vaultId, path);
            }
        }
        existing.setName(basenameOf(path));
        existing.setSha256(stored.sha256());
        existing.setSize(stored.size());
        existing.setDeleted(0);
        existing.setVersion(version);
        existing.setUpdatedAt(LocalDateTime.now());
        attachmentMapper.updateById(existing);
    }

    /** 校验并归一化库内相对路径。规则与 {@link SyncService} 的正文路径一致，只是扩展名白名单不同。 */
    private String validatePath(String path) {
        String trimmed = path == null ? "" : path.trim();
        String problem = null;
        if (trimmed.isEmpty()) {
            problem = "不能为空";
        } else if (trimmed.length() > PATH_MAX) {
            problem = "长度超过 " + PATH_MAX;
        } else if (trimmed.startsWith("/") || trimmed.contains("\\")) {
            problem = "必须是库内相对路径";
        } else {
            for (String segment : trimmed.split("/")) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    problem = "路径段不能是 . / .. / 空";
                    break;
                }
                if (segment.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                    // 控制字符会让 Content-Disposition 这类响应头没法安全构造
                    problem = "路径不能含控制字符";
                    break;
                }
            }
        }
        if (problem == null && !ALLOWED_EXTENSIONS.contains(extensionOf(trimmed))) {
            problem = "只同步 " + String.join(" / ", ALLOWED_EXTENSIONS.stream().sorted().toList());
        }
        if (problem != null) {
            throw new BizException(HttpStatus.BAD_REQUEST, "path: " + path + " " + problem);
        }
        return trimmed;
    }

    private static String basenameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** 取文件名部分的扩展名（小写）；目录名里的点不算。 */
    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash + 1 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** Content-Type：按扩展名映射（不是客户端说的类型），未知扩展名一律八位字节流。 */
    public static String mimeOf(String filename) {
        return MIME_BY_EXT.getOrDefault(extensionOf(filename), "application/octet-stream");
    }

    private BizException quotaExceeded() {
        return new BizException(HttpStatus.PAYLOAD_TOO_LARGE,
                "仓库附件配额已满（上限 " + (quotaBytes / 1024 / 1024) + "MB）");
    }

    /** 待下载的 blob：元数据 + 磁盘位置，由 Controller 组装成响应。 */
    public record Blob(String path, String name, String sha256, long size, String contentType, Path file) {}
}
