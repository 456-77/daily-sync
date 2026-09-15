package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.common.BizException;
import com.dailysync.dto.AttachmentDeleteResponse;
import com.dailysync.dto.AttachmentUploadResponse;
import com.dailysync.entity.Vault;
import com.dailysync.service.AttachmentService;
import com.dailysync.service.VaultService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 附件上传 / 下载 / 删除（JWT 保护，走 {@link com.dailysync.auth.AuthInterceptor}）。
 *
 * <p>两套寻址方式并存，各自沿用本侧既有约定：
 * <ul>
 *   <li><b>插件</b>只知道 Obsidian 库名 → {@code /api/v1/sync/attachments?vault=<库名>}，
 *       与 {@link SyncController} 一致；上传按名 find-or-create 仓库（首次同步建仓）；</li>
 *   <li><b>网页端</b>只有仓库 id → {@code /api/v1/vaults/{id}/attachments}，
 *       与 {@link RecordController} 一致，归属校验走 {@link VaultService#ownedVault}。</li>
 * </ul>
 * 两条下载入口刻意放在同一个类里：安全响应头（类型、nosniff、缓存）只写一遍，
 * 分散到两个类容易出现只改一处的漂移。
 *
 * <p>上传是<b>原始字节流</b>（{@code Content-Type: application/octet-stream}）而不是 multipart：
 * Obsidian 的 {@code requestUrl} 直接吃 ArrayBuffer，multipart 边界得在插件里手搓；
 * 原始流还能边读边哈希边计数，天然流式，也省掉 spring.servlet.multipart 的配置。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final VaultService vaultService;

    // ------------------------------------------------------------
    // 插件侧：按仓库名寻址
    // ------------------------------------------------------------

    /**
     * 上传或覆盖一个附件。body 即文件字节。
     *
     * <p>幂等：同一路径上传同一份内容返回 status=unchanged，且<b>不推进仓库版本号</b>，
     * 网络重试或重复推送不会惊动其他设备。
     *
     * <p>错误：400 路径/类型不合法（只允许 png/jpg/jpeg/gif/webp/pdf）；
     * 413 超过单文件上限（默认 10MB）或仓库配额（默认 1GB）。
     */
    @PostMapping("/api/v1/sync/attachments")
    public ApiResponse<AttachmentUploadResponse> upload(@RequestParam("vault") String vault,
                                                        @RequestParam("path") String path,
                                                        HttpServletRequest request) {
        Long vaultId = vaultService.findOrCreateVault(UserContext.userId(), vault).getId();
        try {
            return ApiResponse.ok(attachmentService.store(vaultId, path, request.getInputStream()));
        } catch (IOException e) {
            log.error("读取上传内容失败 vaultId={} path={}", vaultId, path, e);
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "读取上传内容失败");
        }
    }

    /**
     * 删除附件（置墓碑）。对未知路径是无操作（status=unchanged，不推进版本号），
     * 与 push 处理未知路径的方式一致，方便客户端无脑重推。
     */
    @DeleteMapping("/api/v1/sync/attachments")
    public ApiResponse<AttachmentDeleteResponse> delete(@RequestParam("vault") String vault,
                                                        @RequestParam("path") String path) {
        Long vaultId = vaultService.findOrCreateVault(UserContext.userId(), vault).getId();
        return ApiResponse.ok(attachmentService.tombstone(vaultId, path));
    }

    /**
     * 下载附件（插件补齐本地文件用）。
     * {@code path} 精确寻址；也可用 {@code name} 按文件名解析（配合 {@code from} 做同目录优先）。
     */
    @GetMapping("/api/v1/sync/attachments")
    public ResponseEntity<Resource> downloadByVaultName(@RequestParam("vault") String vault,
                                                        @RequestParam(required = false) String path,
                                                        @RequestParam(required = false) String name,
                                                        @RequestParam(required = false) String from) {
        Vault owned = vaultService.ownedVaultByName(UserContext.userId(), vault);
        return stream(attachmentService.open(owned.getId(), path, name, from));
    }

    // ------------------------------------------------------------
    // 网页端：按仓库 id 寻址
    // ------------------------------------------------------------

    /**
     * 下载附件（网页端渲染日记里的 {@code ![[截图.png]]} 用）。
     *
     * <p>{@code name} 寻址是为 wiki 嵌入准备的：{@code ![[截图.png]]} 不带目录，
     * Obsidian 按文件名在全库找，解析规则统一放在服务端（同目录优先 → 最短路径 → 字典序），
     * 网页端因此不需要自己维护"文件名 → 路径"的映射。
     */
    @GetMapping("/api/v1/vaults/{id}/attachments")
    public ResponseEntity<Resource> download(@PathVariable Long id,
                                             @RequestParam(required = false) String path,
                                             @RequestParam(required = false) String name,
                                             @RequestParam(required = false) String from) {
        vaultService.ownedVault(UserContext.userId(), id);
        return stream(attachmentService.open(id, path, name, from));
    }

    // ------------------------------------------------------------
    // 响应组装
    // ------------------------------------------------------------

    /** 流式下发 blob：服务端定类型、禁止嗅探、以内容哈希作 ETag。 */
    private static ResponseEntity<Resource> stream(AttachmentService.Blob blob) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(blob.contentType()))
                .contentLength(blob.size())
                // 类型由服务端按扩展名白名单决定，这里再明确禁止浏览器自行嗅探
                .header("X-Content-Type-Options", "nosniff")
                // 告诉调用方「这份字节在库里是哪个路径」。插件按文件名（name=）来取时，
                // 只有服务端知道它解析到了哪个路径（同目录优先 → 最短路径 → 字典序），
                // 而插件必须把文件落在同一个路径上，否则下一轮扫描会把它当成另一个附件重复上传
                .header("X-Attachment-Path", encodeHeaderValue(blob.path()))
                // 按 path 寻址，内容可能随 path 变化，因此不能用 immutable 缓存；
                // 换内容必然换 sha256，用 ETag 让浏览器反复验证而不是反复重下
                .header(HttpHeaders.ETAG, "\"" + blob.sha256() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0, must-revalidate")
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(blob.name()))
                .body(new FileSystemResource(blob.file()));
    }

    /** 响应头只能承载 ISO-8859-1：路径里的中文必须百分号编码（空格用 %20 而非 +）。 */
    private static String encodeHeaderValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Content-Disposition 用 RFC 5987 的 {@code filename*=UTF-8''<pct-encoded>} 形式：
     * 文件名整体百分号编码，不拼原始引号也不放裸 name，文件名里若带引号/换行
     * 也不会破坏响应头。空格要编码成 %20 而不是 URLEncoder 默认的 +。
     */
    private static String contentDisposition(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "inline; filename*=UTF-8''" + encoded;
    }
}
