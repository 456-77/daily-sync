package com.dailysync.dto;

import java.time.LocalDateTime;

/**
 * 一个可分发的安装包（M9）：downloads 目录里一个已识别的安装包文件。
 *
 * @param channel    通道：stable（稳定版）/ beta（测试版），即所在子目录
 * @param version    从文件名解析的版本号（如 0.39.0、0.40.0-beta.1）
 * @param fileName   文件名（不含目录），下载走 file 端点时回传
 * @param kind       平台种类：windows-setup / windows-msi / android-apk
 * @param sizeBytes  文件大小
 * @param modifiedAt 文件修改时间（门户展示用）
 */
public record DownloadItemResponse(String channel, String version, String fileName,
                                   String kind, long sizeBytes, LocalDateTime modifiedAt) {
}
