package com.dailysync.dto;

/**
 * 某通道最新版本概要（M9），给 quick-note 检查更新用。
 *
 * <p>notes 来自同目录可选的 {@code notes-<版本>.txt}（缺省 null）；apkName / setupName
 * 是该版本对应的文件名，客户端自行拼 file 端点 URL（不回传绝对地址——服务端
 * 不需要知道自己的对外地址，客户端手里有 serverUrl）。
 *
 * @param version   最新版本号，目录里没有可识别的包时为 null（客户端视为「无更新」）
 * @param notes     更新说明，可空
 * @param apkName   最新版的安卓 APK 文件名，可空（该版本没传 APK）
 * @param setupName 最新版的 Windows 安装器（setup.exe）文件名，可空
 */
public record DownloadLatestResponse(String version, String notes, String apkName, String setupName) {
}
