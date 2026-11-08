package com.dailysync.service;

import com.dailysync.common.BizException;
import com.dailysync.dto.DownloadItemResponse;
import com.dailysync.dto.DownloadLatestResponse;
import com.dailysync.dto.DownloadProductResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 安装包分发服务（M9）：quick-note 的安装包在本地构建后由用户 scp 到服务器的
 * 固定目录（国内 → 阿里云直连很快，不必过 GitHub CDN），这里扫描目录、解析
 * 文件名并对外提供 列表 / 最新版 / Tauri 更新清单 / 文件下载。
 *
 * <p>目录约定（M12 起支持多软件）：{@code <dir>/<product>/stable/} 与
 * {@code <dir>/<product>/beta/}，文件名形如 {@code QuickNote_0.39.0_x64-setup.exe}、
 * {@code QuickNote_0.39.0_x64-setup.exe.sig}、{@code QuickNote_0.39.0_android_arm64.apk}，
 * 版本号段为 {@code X.Y.Z} 或 {@code X.Y.Z-beta.N}。.sig 是 Tauri updater 的签名
 * 伴生文件，不进列表，只被 tauri-manifest 端点读取；可选的 {@code notes-<版本>.txt}
 * 会作为该版本的更新说明回给客户端（更新弹窗展示）。
 *
 * <p>Quick Note 保留旧的 {@code <dir>/stable}、{@code <dir>/beta} 布局：存在
 * {@code <dir>/quick-note/} 时优先用新布局，否则回退旧根目录。这样服务器上已有的
 * 包不用迁移即可继续服务；一旦创建了 {@code downloads/quick-note/}，旧根目录就
 * 不再被扫描——迁移必须一次把 stable/beta 全部搬过去（文档已写明）。
 *
 * <p>不自动删旧文件：「最新几版」由列表端点按版本排序、门户取前 N 展示，
 * 清理旧包是 ssh rm 的一条手动命令——绝不悄悄动用户 scp 上来的文件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    /** Quick Note 的产品 id 同时是它的目录名；旧的根 stable/beta 也归属这个产品。 */
    public static final String PRODUCT_QUICK_NOTE = "quick-note";
    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_BETA = "beta";
    private static final Set<String> CHANNELS = Set.of(CHANNEL_STABLE, CHANNEL_BETA);
    /** 这两个目录名永远表示通道，不能被当成软件目录（否则 catalog 会出现 stable 分类）。 */
    private static final Set<String> RESERVED_CHANNEL_DIRS = Set.of(CHANNEL_STABLE, CHANNEL_BETA);

    /** 软件目录可选元数据文件；只读展示名/描述/官网，不参与扫描列表。 */
    private static final String PRODUCT_META_FILE = "product.json";

    /**
     * 软件目录名白名单：小写字母/数字/连字符，首字符不能是连字符。
     * 这个字符串会拼进磁盘路径，严格白名单比事后 normalize 更省心（也避免
     * 匿名接口通过 product 参数制造任意目录）。中文名放 product.json 的 name。
     */
    private static final Pattern PRODUCT_ID_OK =
            Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");

    /** Quick Note 的内置元数据：没有 product.json 时也能在下载中心显示完整信息。 */
    private static final ProductInfo QUICK_NOTE_INFO = new ProductInfo(
            PRODUCT_QUICK_NOTE,
            "Quick Note",
            "Windows 与 Android 双端 Markdown 笔记，数据云端随行。",
            "https://github.com/456-77/quick-note");

    /** 文件名里的版本号段；kind 也按扩展名判定，只认这三种安装包 */
    private static final Pattern VERSION_IN_NAME =
            Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:-beta\\.(\\d+))?");

    /**
     * 文件名白名单：首字符字母/数字（顺带拒绝点开头的隐藏文件），整体只含
     * 字母/数字/点/下划线/连字符，≤255 字符——一次把路径穿越（.. 与分隔符）、
     * 控制字符、Unicode 混写全部挡在门外，下载端点因此无需再猜「合不合法」
     */
    private static final Pattern FILE_NAME_OK =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$");

    /** 下载端点返回的内容类型：apk 给专用类型便于安卓浏览器识别，其余一律八位字节流 */
    private static final String APK_CONTENT_TYPE = "application/vnd.android.package-archive";

    private final ObjectMapper objectMapper;

    @Value("${daily-sync.downloads.dir:./data/downloads}")
    private String baseDir;

    /** 打开的下载文件（同 AttachmentService.Blob 的思路：元数据 + 磁盘路径） */
    public record Blob(Path file, String name, long size, String contentType) {}

    /** tauri-manifest 端点的原料：Controller 负责把 setupName 拼成绝对下载直链 */
    public record TauriManifest(String version, String pubDate, String notes,
                                String signature, String setupName) {}

    /** 一个软件目录的展示元数据（catalog 用，不参与文件扫描）。 */
    private record ProductInfo(String id, String name, String description, String website) {}

    // ------------------------------------------------------------
    // 列表与最新版
    // ------------------------------------------------------------

    /**
     * Quick Note 的全量安装包：稳定版在前，通道内按版本降序。
     * 保持 M9 的接口契约不变——{@code /api/v1/downloads} 只服务 Quick Note，
     * 其他软件走 {@link #catalog()}。
     */
    public List<DownloadItemResponse> list() {
        List<DownloadItemResponse> items = new ArrayList<>();
        items.addAll(scan(PRODUCT_QUICK_NOTE, CHANNEL_STABLE));
        items.addAll(scan(PRODUCT_QUICK_NOTE, CHANNEL_BETA));
        return sortItems(items);
    }

    /**
     * 下载中心的全部软件分类（M12）：Quick Note 固定排第一，其余按目录名升序。
     *
     * <p>软件目录通过 {@code downloads/<id>/<stable|beta>/} 自动发现，可选的
     * {@code product.json} 提供展示名/描述/官网——新增软件只要上传包并放一个
     * 元数据文件，不需要改代码或重新发版。没有通道目录也没有 product.json 的
     * 普通目录会被忽略，避免把 data 下的杂项误报成软件。
     */
    public List<DownloadProductResponse> catalog() {
        List<DownloadProductResponse> products = new ArrayList<>();
        for (ProductInfo info : discoverProducts()) {
            List<DownloadItemResponse> items = new ArrayList<>();
            items.addAll(scan(info.id(), CHANNEL_STABLE));
            items.addAll(scan(info.id(), CHANNEL_BETA));
            products.add(new DownloadProductResponse(info.id(), info.name(), info.description(),
                    info.website(), sortItems(items)));
        }
        return products;
    }

    /** Quick Note 某通道最新版本概要；目录里没有可识别的包时 version=null（客户端视为无更新）。 */
    public DownloadLatestResponse latest(String channel) {
        requireChannel(channel);
        List<DownloadItemResponse> items = scan(PRODUCT_QUICK_NOTE, channel);
        if (items.isEmpty()) {
            return new DownloadLatestResponse(null, null, null, null);
        }
        items.sort((a, b) -> {
            int byVersion = compareVersionsDesc(a.version(), b.version());
            return byVersion != 0 ? byVersion : a.fileName().compareTo(b.fileName());
        });
        String version = items.get(0).version();
        String apkName = items.stream()
                .filter(i -> version.equals(i.version()) && "android-apk".equals(i.kind()))
                .map(DownloadItemResponse::fileName).findFirst().orElse(null);
        String setupName = items.stream()
                .filter(i -> version.equals(i.version()) && "windows-setup".equals(i.kind()))
                .map(DownloadItemResponse::fileName).findFirst().orElse(null);
        return new DownloadLatestResponse(version, readNotes(PRODUCT_QUICK_NOTE, channel, version),
                apkName, setupName);
    }

    // ------------------------------------------------------------
    // Tauri 更新清单（桌面测试版应用内更新）
    // ------------------------------------------------------------

    /**
     * 组装 Tauri updater 清单的原料：取通道内最新的 Windows 安装器，签名从
     * 同名 .sig 伴生文件读取。缺 .sig 直接报错——签名对不上的更新装不上去，
     * 宁可让上传者补传也别让用户揣着一个永远失败的更新通道。
     */
    public TauriManifest tauriManifest(String channel) {
        requireChannel(channel);
        List<DownloadItemResponse> items = scan(PRODUCT_QUICK_NOTE, channel).stream()
                .filter(i -> "windows-setup".equals(i.kind()))
                .sorted((a, b) -> compareVersionsDesc(a.version(), b.version()))
                .toList();
        if (items.isEmpty()) {
            throw new BizException(HttpStatus.NOT_FOUND, "该通道还没有 Windows 安装包");
        }
        DownloadItemResponse setup = items.get(0);
        Path sigFile = channelDir(PRODUCT_QUICK_NOTE, channel, false).resolve(setup.fileName() + ".sig");
        if (!Files.isRegularFile(sigFile)) {
            throw new BizException(HttpStatus.NOT_FOUND,
                    "缺少签名文件 " + setup.fileName() + ".sig，请连同 .sig 一起上传");
        }
        try {
            String signature = Files.readString(sigFile, StandardCharsets.UTF_8).trim();
            return new TauriManifest(setup.version(), isoInstant(setup.modifiedAt()),
                    readNotes(PRODUCT_QUICK_NOTE, channel, setup.version()), signature, setup.fileName());
        } catch (IOException e) {
            log.error("读取签名文件失败 {}", sigFile, e);
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "读取签名文件失败");
        }
    }

    // ------------------------------------------------------------
    // 文件下载
    // ------------------------------------------------------------

    /**
     * 打开一个下载文件。product 缺省为 Quick Note（旧门户/客户端的链接不带这个参数）；
     * 非 Quick Note 必须是已发现的软件目录，避免匿名请求拿任意字符串拼路径。
     * 文件名走白名单校验，路径穿越在这里就被拒掉。
     */
    public Blob open(String product, String channel, String name) {
        String productId = requireProduct(product);
        requireChannel(channel);
        if (name == null || !FILE_NAME_OK.matcher(name).matches()) {
            throw new BizException(HttpStatus.BAD_REQUEST, "文件名不合法");
        }
        // root 必须与 file 同口径（绝对路径 + normalize）：相对 baseDir（./data/...）下
        // file.normalize() 会剥掉 "./" 前缀而 root 还带着，startsWith 恒为 false → 全部 404
        Path root = channelDir(productId, channel, false).toAbsolutePath().normalize();
        Path file = root.resolve(name).normalize();
        // 白名单已保证 name 无分隔符；normalize + startsWith 是第二道闸（防御性编程）
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new BizException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        try {
            long size = Files.size(file);
            String contentType = name.toLowerCase(Locale.ROOT).endsWith(".apk")
                    ? APK_CONTENT_TYPE : "application/octet-stream";
            return new Blob(file, name, size, contentType);
        } catch (IOException e) {
            log.error("读取安装包失败 {}", file, e);
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "读取安装包失败");
        }
    }

    // ------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------

    /**
     * 扫描一个产品的通道目录：识别安装包、解析版本，不可识别的文件（含 .sig
     * 与 product.json）静默跳过。Quick Note 首次部署时按需创建通道目录；
     * 其他产品只读，不存在就返回空列表（不能因为一次匿名 GET 就在磁盘上造目录）。
     */
    private List<DownloadItemResponse> scan(String product, String channel) {
        Path dir = channelDir(product, channel, PRODUCT_QUICK_NOTE.equals(product));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<DownloadItemResponse> items = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if (name.endsWith(".sig") || !FILE_NAME_OK.matcher(name).matches()) {
                    return;
                }
                String kind = kindOf(name);
                if (kind == null) {
                    return;
                }
                Matcher m = VERSION_IN_NAME.matcher(name);
                if (!m.find()) {
                    return;
                }
                String version = m.group();
                try {
                    items.add(new DownloadItemResponse(channel, version, name, kind,
                            Files.size(path), LocalDateTime.ofInstant(
                                    Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault())));
                } catch (IOException e) {
                    log.warn("读取安装包元数据失败 {}", path, e);
                }
            });
        } catch (IOException e) {
            log.error("扫描安装包目录失败 {}", dir, e);
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "扫描安装包目录失败");
        }
        return items;
    }

    /** 可选更新说明：同目录 notes-&lt;版本&gt;.txt，截到 8KB 防止异常大文件拖累接口。 */
    private String readNotes(String product, String channel, String version) {
        Path notes = channelDir(product, channel, false).resolve("notes-" + version + ".txt");
        if (!Files.isRegularFile(notes)) {
            return null;
        }
        try {
            String text = Files.readString(notes, StandardCharsets.UTF_8).trim();
            return text.length() > 8192 ? text.substring(0, 8192) : text;
        } catch (IOException e) {
            log.warn("读取更新说明失败 {}", notes, e);
            return null;
        }
    }

    /**
     * 发现所有软件目录：Quick Note 永远在列表里（哪怕还没传包），其余目录必须
     * 至少有一个通道目录或 product.json 才认。快速失败只影响 catalog 的附加项，
     * 不会让整个下载中心 500。
     */
    private List<ProductInfo> discoverProducts() {
        List<ProductInfo> products = new ArrayList<>();
        Path root = Paths.get(baseDir);
        Path quickNoteDir = root.resolve(PRODUCT_QUICK_NOTE);
        // Quick Note 有专属目录时读它的 product.json；沿用旧根目录时用内置元数据，
        // 不去读根目录下的 product.json——那里还躺着 stable/beta，不该被当成软件元数据。
        products.add(Files.isDirectory(quickNoteDir)
                ? readProductInfo(PRODUCT_QUICK_NOTE, quickNoteDir, QUICK_NOTE_INFO)
                : QUICK_NOTE_INFO);

        if (!Files.isDirectory(root)) {
            return products;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(dir -> {
                        String id = dir.getFileName().toString();
                        if (PRODUCT_QUICK_NOTE.equals(id) || RESERVED_CHANNEL_DIRS.contains(id)
                                || !PRODUCT_ID_OK.matcher(id).matches()) {
                            return;
                        }
                        if (!hasChannelDirs(dir)
                                && !Files.isRegularFile(dir.resolve(PRODUCT_META_FILE))) {
                            return;
                        }
                        products.add(readProductInfo(id, dir, null));
                    });
        } catch (IOException e) {
            // 单个坏目录/权限问题不应拖垮整个下载中心：记录后返回已发现的 Quick Note
            log.error("扫描软件目录失败 {}", root, e);
        }
        return products;
    }

    /** 读 product.json 覆盖默认元数据；缺失/损坏/字段为空都回退，官网只放行 http(s)。 */
    private ProductInfo readProductInfo(String id, Path dir, ProductInfo defaults) {
        ProductInfo fallback = defaults != null
                ? defaults
                : new ProductInfo(id, prettifyProductName(id), null, null);
        Path metaFile = dir.resolve(PRODUCT_META_FILE);
        if (!Files.isRegularFile(metaFile)) {
            return fallback;
        }
        try {
            Map<String, String> meta = objectMapper.readValue(metaFile.toFile(),
                    new TypeReference<Map<String, String>>() {});
            return new ProductInfo(id,
                    textOrDefault(meta.get("name"), fallback.name()),
                    textOrDefault(meta.get("description"), fallback.description()),
                    cleanWebsite(meta.get("website")));
        } catch (IOException e) {
            log.warn("读取软件元数据失败 {}", metaFile, e);
            return fallback;
        }
    }

    /** 产品目录解析：Quick Note 专属目录优先，否则回退旧的根 stable/beta。 */
    private Path productDir(String product) {
        Path specific = Paths.get(baseDir, product);
        if (Files.isDirectory(specific)) {
            return specific;
        }
        if (PRODUCT_QUICK_NOTE.equals(product)) {
            return Paths.get(baseDir);
        }
        return specific;
    }

    private static boolean hasChannelDirs(Path dir) {
        return Files.isDirectory(dir.resolve(CHANNEL_STABLE))
                || Files.isDirectory(dir.resolve(CHANNEL_BETA));
    }

    /** 产品名兜底：demo-tool → Demo Tool；中文名放 product.json。 */
    private static String prettifyProductName(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("[-_]")) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.isEmpty() ? id : sb.toString();
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 官网只接受 http(s)：防止 product.json 里塞 javascript: 之类被页面当链接渲染。 */
    private static String cleanWebsite(String website) {
        if (website == null || website.isBlank()) {
            return null;
        }
        String trimmed = website.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? trimmed : null;
    }

    /** 产品 id 校验 + 存在性校验；缺省视为 Quick Note（旧链接兼容）。 */
    private String requireProduct(String product) {
        String id = product == null || product.isBlank() ? PRODUCT_QUICK_NOTE : product.trim();
        if (!PRODUCT_ID_OK.matcher(id).matches()) {
            throw new BizException(HttpStatus.BAD_REQUEST, "product 不合法");
        }
        if (!PRODUCT_QUICK_NOTE.equals(id)
                && discoverProducts().stream().noneMatch(p -> p.id().equals(id))) {
            throw new BizException(HttpStatus.NOT_FOUND, "软件不存在");
        }
        return id;
    }

    /** kind 由扩展名判定；都不认识返回 null（不进列表、不可下载）。 */
    private static String kindOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk")) return "android-apk";
        if (lower.endsWith(".msi")) return "windows-msi";
        if (lower.endsWith(".exe")) return "windows-setup";
        return null;
    }

    /** 通道→版本排序：stable 在前，通道内版本降序，同版本按文件名稳定排序。 */
    private static List<DownloadItemResponse> sortItems(List<DownloadItemResponse> items) {
        List<DownloadItemResponse> sorted = new ArrayList<>(items);
        // 通道按 stable→beta 的固定次序（不能用字符串排——"beta" 字典序在 "stable" 前）
        Comparator<DownloadItemResponse> byChannel = Comparator.comparingInt(i ->
                CHANNEL_STABLE.equals(i.channel()) ? 0 : 1);
        sorted.sort(byChannel
                .thenComparing((a, b) -> compareVersionsDesc(a.version(), b.version()))
                .thenComparing(DownloadItemResponse::fileName));
        return sorted;
    }

    /**
     * 版本降序比较器：major/minor/patch 逐段比，同号时正式版 &gt; beta，
     * beta 之间比序号（beta.2 &gt; beta.1）。约定：beta 的 X.Y.Z 必须**大于**
     * 当前稳定版，稳定版发布后清空 beta 目录——否则测试版用户永远收不到稳定版。
     */
    private static Comparator<String> compareVersionsDesc() {
        return (a, b) -> {
            long[] ka = versionKey(a);
            long[] kb = versionKey(b);
            for (int i = 0; i < ka.length; i++) {
                if (ka[i] != kb[i]) return ka[i] > kb[i] ? -1 : 1;
            }
            return 0;
        };
    }

    private static int compareVersionsDesc(String a, String b) {
        return compareVersionsDesc().compare(a, b);
    }

    private static long[] versionKey(String version) {
        Matcher m = VERSION_IN_NAME.matcher(version);
        if (!m.find()) return new long[]{0, 0, 0, 0, 0};
        String betaSeq = m.group(4);
        return new long[]{
                Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)),
                betaSeq == null ? 1 : 0,
                betaSeq == null ? 0 : Long.parseLong(betaSeq),
        };
    }

    private static String isoInstant(LocalDateTime time) {
        return DateTimeFormatter.ISO_INSTANT.format(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    private static void requireChannel(String channel) {
        if (channel == null || !CHANNELS.contains(channel)) {
            throw new BizException(HttpStatus.BAD_REQUEST, "channel 只支持 stable / beta");
        }
    }

    /**
     * 通道目录。create=true 只用于 Quick Note 的首部署空态自建（M9 行为）；
     * 其他产品与下载/读取路径一律不建目录——匿名请求不能改变磁盘结构。
     */
    private Path channelDir(String product, String channel, boolean create) {
        Path dir = productDir(product).resolve(channel);
        if (!create) {
            return dir;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("创建安装包目录失败 {}", dir, e);
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "安装包目录不可用");
        }
        return dir;
    }
}
