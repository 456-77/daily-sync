package com.dailysync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.common.BizException;
import com.dailysync.dto.AttachmentDeleteResponse;
import com.dailysync.dto.AttachmentUploadResponse;
import com.dailysync.dto.FileInfoResponse;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 附件：库内除正文（.md / .json）之外的<b>任意类型文件</b>——图片、PDF，也包括
 * docx / mp3 / 无扩展名等一切二进制与文本。全类型同步（M8）后附件通道即通用文件通道。
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
 *
 * <p>类型放开的代价由下载侧兜住：不在 {@link #INLINE_EXTENSIONS} 里的类型，
 * 下载响应一律 {@code Content-Disposition: attachment}（见 {@link #isInline}）——
 * svg / html 这类能内联脚本的文件不再被浏览器直出渲染，XSS 通道随之关闭；
 * {@code <img>} 标签加载不受 disposition 影响，网页端贴图渲染照旧。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    /**
     * 允许浏览器内联（直出渲染）的类型。只有确定无脚本执行风险的位图与 PDF。
     * 其余类型（svg、html、js……一切）都<b>允许上传</b>，但下载时强制走 attachment
     * 下载而非内联——放开同步不等于放开渲染。
     */
    private static final Set<String> INLINE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "pdf");

    /**
     * 扩展名 -> Content-Type。服务端自己映射，不采信客户端传来的类型；未知扩展名一律八位字节流。
     * svg 必须映成 image/svg+xml 而不是落进八位字节流：nosniff 开着，字节流类型的 SVG
     * 在 {@code <img>} 里不渲染（网页端文件页/正文引用都是 blob + img 取图）。
     * 直出安全不用担心——svg 的 Content-Disposition 已强制 attachment，此处只影响
     * {@code <img>} 加载（SVG 静态安全模式，脚本不执行）。
     */
    private static final Map<String, String> MIME_BY_EXT = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "svg", "image/svg+xml",
            "pdf", "application/pdf");

    /** path 列宽 VARCHAR(512)；name 列宽 VARCHAR(255) */
    private static final int PATH_MAX = 512;
    private static final int NAME_MAX = 255;

    /**
     * 库内配置文件不参与同步（2026-11-12 事故防线）：quick-daily-note.json 是
     * quick-daily-note 插件的设备级配置，可能含 emailAccessKey 一类凭据，M8 全类型
     * 同步把它当普通附件双向上云、多端旧副本互相覆盖。泄露副本已人工删除，这道闸
     * 挡住旧版客户端（quick-note &lt;0.39.3，双侧排除该文件之前的版本）继续上传；
     * 新版客户端自己也不会再传。只拦库根的这份文件——子目录里同名的用户文件不误伤。
     */
    private static final String CONFIG_FILE_PATH = "quick-daily-note.json";

    private final AttachmentMapper attachmentMapper;
    private final VaultService vaultService;
    private final AttachmentStore blobStore;

    /** 单文件上限（默认 50MB），超限 413 */
    @Value("${daily-sync.attachments.max-file-bytes:52428800}")
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
     * @throws BizException 400 路径不合法；403 库内配置文件（见 {@link #CONFIG_FILE_PATH}）；
     *                     413 超单文件上限或仓库配额
     */
    @Transactional
    public AttachmentUploadResponse store(Long vaultId, String path, InputStream in) {
        String cleanPath = validatePath(path);
        if (CONFIG_FILE_PATH.equals(cleanPath)) {
            // 403 给旧版客户端一个明确的失败（不再传成功），重装新版后自然消失；
            // 删除（tombstone）不拦——旧副本能删是好事
            throw new BizException(HttpStatus.FORBIDDEN, "库内配置文件不参与同步");
        }
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
     * 列出仓库内全部正常附件（网页端「文件」页签用）。
     *
     * <p>M8 后 attachments 表即「仓库内除正文外的全部文件」，列表不再局限于图片/PDF。
     * 只回元数据不回字节；墓碑（deleted=1）不下发。个人库的量级（几百到几千行）
     * 一次取回没有分页的必要，按 path 排序让前端按目录分组时天然有序。
     */
    public List<FileInfoResponse> list(Long vaultId) {
        return attachmentMapper.selectList(Wrappers.<Attachment>lambdaQuery()
                        .eq(Attachment::getVaultId, vaultId)
                        .eq(Attachment::getDeleted, 0)
                        .orderByAsc(Attachment::getPath))
                .stream()
                .map(row -> new FileInfoResponse(row.getPath(), row.getName(), row.getSize(),
                        mimeOf(row.getName()), isInline(row.getName()), row.getUpdatedAt()))
                .toList();
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
                mimeOf(row.getName()), isInline(row.getName()), blob);
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

    /**
     * 校验并归一化库内相对路径。规则与 {@link SyncService} 的正文路径一致，
     * 但<b>不限扩展名</b>——全类型同步后附件通道接受一切文件。文件名本身仍受限：
     * basename 超 {@link #NAME_MAX} 会被数据库截断报错，不如在这里给出明确的 400。
     */
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
        if (problem == null && basenameOf(trimmed).length() > NAME_MAX) {
            problem = "文件名超过 " + NAME_MAX + " 字符";
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

    /**
     * 该附件是否允许浏览器内联渲染（决定下载响应的 Content-Disposition）。
     * 只有 {@link #INLINE_EXTENSIONS} 里的位图与 PDF 内联；svg / html 等可执行脚本的
     * 类型强制走 attachment 下载，浏览器直出的 XSS 通道由此关闭。
     */
    public static boolean isInline(String filename) {
        return INLINE_EXTENSIONS.contains(extensionOf(filename));
    }

    private BizException quotaExceeded() {
        return new BizException(HttpStatus.PAYLOAD_TOO_LARGE,
                "仓库附件配额已满（上限 " + (quotaBytes / 1024 / 1024) + "MB）");
    }

    /** 待下载的 blob：元数据 + 磁盘位置，由 Controller 组装成响应。inline 决定 Content-Disposition。 */
    public record Blob(String path, String name, String sha256, long size, String contentType,
                       boolean inline, Path file) {}
}
