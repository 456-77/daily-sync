package com.dailysync.controller;

import com.dailysync.common.ApiResponse;
import com.dailysync.dto.DownloadItemResponse;
import com.dailysync.dto.DownloadLatestResponse;
import com.dailysync.dto.DownloadProductResponse;
import com.dailysync.service.DownloadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安装包分发（M9，匿名可访问——JWT 在 WebMvcConfig 里对 /api/v1/downloads/** 放行）。
 *
 * <p>五件事：Quick Note 列表（门户下载区渲染）、全部软件分类（M12 下载中心）、
 * 最新版概要（quick-note 检查更新）、Tauri 更新清单（桌面应用内更新，<b>裸 JSON
 * 不包 ApiResponse 信封</b>——updater 插件自己解析这份结构，信封只会让它报
 * 「响应格式不对」）、文件下载（浏览器/APK 下载器直接访问的直链）。
 *
 * <p>旧的 {@code /api/v1/downloads}、{@code /latest}、{@code /tauri-manifest}
 * 继续只服务 Quick Note（客户端契约不变）；{@code /catalog} 与带 {@code product}
 * 参数的 {@code /file} 服务多软件下载中心。
 *
 * <p>本类所有端点都是只读的目录扫描，逐条操作不写审计日志（对齐 M5 不变量：
 * 高频读操作不把安全日志刷成流水账）。
 */
@RestController
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadService downloadService;

    /** Quick Note 全量安装包（稳定版在前、通道内版本降序），门户取前 N 展示。 */
    @GetMapping("/api/v1/downloads")
    public ApiResponse<List<DownloadItemResponse>> list() {
        return ApiResponse.ok(downloadService.list());
    }

    /**
     * 下载中心的全部软件分类（M12）：每个软件带展示元数据与全部安装包。
     * 主页只用真正的 Quick Note 列表，本端点供 /downloads/ 全部安装包页使用。
     */
    @GetMapping("/api/v1/downloads/catalog")
    public ApiResponse<List<DownloadProductResponse>> catalog() {
        return ApiResponse.ok(downloadService.catalog());
    }

    /**
     * 某通道最新版本概要。version=null 表示该通道还没有包，客户端视为「无更新」。
     */
    @GetMapping("/api/v1/downloads/latest")
    public ApiResponse<DownloadLatestResponse> latest(@RequestParam("channel") String channel) {
        return ApiResponse.ok(downloadService.latest(channel));
    }

    /**
     * Tauri updater 原生清单格式（桌面测试版应用内更新用）。
     * 下载直链用请求的 scheme/host/port 反推成绝对地址——updater 需要能独立
     * 访问这个 URL，不能靠相对路径。
     */
    @GetMapping(value = "/api/v1/downloads/tauri-manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> tauriManifest(@RequestParam("channel") String channel,
                                             HttpServletRequest request) {
        DownloadService.TauriManifest m = downloadService.tauriManifest(channel);
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("signature", m.signature());
        platform.put("url", baseUrl(request) + "/api/v1/downloads/file?channel="
                + channel + "&name=" + encodeUrl(m.setupName()));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", m.version());
        manifest.put("pub_date", m.pubDate());
        manifest.put("notes", m.notes() == null ? "" : m.notes());
        manifest.put("platforms", Map.of("windows-x86_64", platform));
        return manifest;
    }

    /**
     * 下载安装包。product 缺省为 Quick Note（旧门户/客户端链接不带该参数）；
     * 下载中心的其他软件显式带上 product。与附件下载同一套安全头：类型由服务端
     * 按扩展名定、nosniff 恒挂、一律 attachment 直下（安装包没有需要内联渲染的场景）。
     */
    @GetMapping("/api/v1/downloads/file")
    public ResponseEntity<Resource> file(
            @RequestParam(value = "product", required = false,
                    defaultValue = DownloadService.PRODUCT_QUICK_NOTE) String product,
            @RequestParam("channel") String channel,
            @RequestParam("name") String name) {
        DownloadService.Blob blob = downloadService.open(product, channel, name);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(blob.contentType()))
                .contentLength(blob.size())
                .header("X-Content-Type-Options", "nosniff")
                // 同名文件内容不可变（内容变了必然换版本号），可以放心给浏览器缓存
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(blob.name()))
                .body(new FileSystemResource(blob.file()));
    }

    // ------------------------------------------------------------
    // 响应组装
    // ------------------------------------------------------------

    /** 从请求反推对外地址（http://host:port）；标准端口不拼端口号。 */
    private static String baseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private static String encodeUrl(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Content-Disposition 用 RFC 5987 形式，与附件下载同口径（空格 %20 不用 +）。 */
    private static String contentDisposition(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }
}
