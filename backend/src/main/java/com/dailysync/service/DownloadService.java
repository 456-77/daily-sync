package com.dailysync.service;

import com.dailysync.common.BizException;
import com.dailysync.dto.DownloadItemResponse;
import com.dailysync.dto.DownloadLatestResponse;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 安装包分发服务（M9）：quick-note 的安装包在本地构建后由用户 scp 到服务器的
 * 固定目录（国内 → 阿里云直连很快，不必过 GitHub CDN），这里扫描目录、解析
 * 文件名并对外提供 列表 / 最新版 / Tauri 更新清单 / 文件下载。
 *
 * <p>目录约定：{@code <dir>/stable/} 与 {@code <dir>/beta/} 两个子目录，文件名
 * 形如 {@code QuickNote_0.39.0_x64-setup.exe}、{@code QuickNote_0.39.0_x64-setup.exe.sig}、
 * {@code QuickNote_0.39.0_android_arm64.apk}，版本号段为 {@code X.Y.Z} 或
 * {@code X.Y.Z-beta.N}。.sig 是 Tauri updater 的签名伴生文件，不进列表，
 * 只被 tauri-manifest 端点读取；可选的 {@code notes-<版本>.txt} 会作为该版本
 * 的更新说明回给客户端（更新弹窗展示）。
 *
 * <p>不自动删旧文件：「最新几版」由列表端点按版本排序、门户取前 N 展示，
 * 清理旧包是 ssh rm 的一条手动命令——绝不悄悄动用户 scp 上来的文件。
 */
@Slf4j
@Service
public class DownloadService {

    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_BETA = "beta";
    private static final Set<String> CHANNELS = Set.of(CHANNEL_STABLE, CHANNEL_BETA);

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

    @Value("${daily-sync.downloads.dir:./data/downloads}")
    private String baseDir;

    /** 打开的下载文件（同 AttachmentService.Blob 的思路：元数据 + 磁盘路径） */
    public record Blob(Path file, String name, long size, String contentType) {}

    /** tauri-manifest 端点的原料：Controller 负责把 setupName 拼成绝对下载直链 */
    public record TauriManifest(String version, String pubDate, String notes,
                                String signature, String setupName) {}

    // ------------------------------------------------------------
    // 列表与最新版
    // ------------------------------------------------------------

    /** 全量列出两个通道里可识别的安装包：稳定版在前，通道内按版本降序。 */
    public List<DownloadItemResponse> list() {
        List<DownloadItemResponse> items = new ArrayList<>();
        items.addAll(scan(CHANNEL_STABLE));
        items.addAll(scan(CHANNEL_BETA));
        // 通道按 stable→beta 的固定次序（不能用字符串排——"beta" 字典序在 "stable" 前）
        Comparator<DownloadItemResponse> byChannel = Comparator.comparingInt(i ->
                CHANNEL_STABLE.equals(i.channel()) ? 0 : 1);
        items.sort(byChannel
                .thenComparing((a, b) -> compareVersionsDesc(a.version(), b.version()))
                .thenComparing(DownloadItemResponse::fileName));
        return items;
    }

    /** 某通道最新版本概要；目录里没有可识别的包时 version=null（客户端视为无更新）。 */
    public DownloadLatestResponse latest(String channel) {
        requireChannel(channel);
        List<DownloadItemResponse> items = scan(channel);
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
        return new DownloadLatestResponse(version, readNotes(channel, version), apkName, setupName);
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
        List<DownloadItemResponse> items = scan(channel).stream()
                .filter(i -> "windows-setup".equals(i.kind()))
                .sorted((a, b) -> compareVersionsDesc(a.version(), b.version()))
                .toList();
        if (items.isEmpty()) {
            throw new BizException(HttpStatus.NOT_FOUND, "该通道还没有 Windows 安装包");
        }
        DownloadItemResponse setup = items.get(0);
        Path sigFile = channelDir(channel).resolve(setup.fileName() + ".sig");
        if (!Files.isRegularFile(sigFile)) {
            throw new BizException(HttpStatus.NOT_FOUND,
                    "缺少签名文件 " + setup.fileName() + ".sig，请连同 .sig 一起上传");
        }
        try {
            String signature = Files.readString(sigFile, StandardCharsets.UTF_8).trim();
            return new TauriManifest(setup.version(), isoInstant(setup.modifiedAt()),
                    readNotes(channel, setup.version()), signature, setup.fileName());
        } catch (IOException e) {
            log.error("读取签名文件失败 {}", sigFile, e);
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "读取签名文件失败");
        }
    }

    // ------------------------------------------------------------
    // 文件下载
    // ------------------------------------------------------------

    /** 打开一个下载文件。文件名走白名单校验，路径穿越在这里就被拒掉。 */
    public Blob open(String channel, String name) {
        requireChannel(channel);
        if (name == null || !FILE_NAME_OK.matcher(name).matches()) {
            throw new BizException(HttpStatus.BAD_REQUEST, "文件名不合法");
        }
        // root 必须与 file 同口径（绝对路径 + normalize）：相对 baseDir（./data/...）下
        // file.normalize() 会剥掉 "./" 前缀而 root 还带着，startsWith 恒为 false → 全部 404
        Path root = channelDir(channel).toAbsolutePath().normalize();
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

    /** 扫描一个通道目录：识别安装包、解析版本，不可识别的文件（含 .sig）静默跳过。 */
    private List<DownloadItemResponse> scan(String channel) {
        Path dir = channelDir(channel);
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

    /** kind 由扩展名判定；都不认识返回 null（不进列表、不可下载）。 */
    private static String kindOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".apk")) return "android-apk";
        if (lower.endsWith(".msi")) return "windows-msi";
        if (lower.endsWith(".exe")) return "windows-setup";
        return null;
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

    /** 可选的更新说明：同目录 notes-&lt;版本&gt;.txt，截到 8KB 防止异常大文件拖累接口。 */
    private String readNotes(String channel, String version) {
        Path notes = channelDir(channel).resolve("notes-" + version + ".txt");
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

    private static String isoInstant(LocalDateTime time) {
        return DateTimeFormatter.ISO_INSTANT.format(time.atZone(ZoneId.systemDefault()).toInstant());
    }

    private static void requireChannel(String channel) {
        if (channel == null || !CHANNELS.contains(channel)) {
            throw new BizException(HttpStatus.BAD_REQUEST, "channel 只支持 stable / beta");
        }
    }

    /** 通道目录；不存在则顺手创建（首次部署/首次上传前的空态也能正常响应）。 */
    private Path channelDir(String channel) {
        Path dir = Paths.get(baseDir, channel);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("创建安装包目录失败 {}", dir, e);
            throw new BizException(HttpStatus.INTERNAL_SERVER_ERROR, "安装包目录不可用");
        }
        return dir;
    }
}
