package com.dailysync.dto;

import java.time.LocalDateTime;

/**
 * 仓库内一个非正文字节（attachments 表一行，M8 后即「全部非 .md/.json 文件」）。
 *
 * @param path        库内相对路径
 * @param name        文件名（basename）
 * @param size        字节数
 * @param contentType 服务端按扩展名定的类型（未知扩展名一律八位字节流），前端不用再判一遍
 * @param inline      是否允许浏览器内联渲染（png/jpg/jpeg/gif/webp/pdf；svg 因安全强制下载，
 *                    但 {@code <img>} 仍可加载）——下载响应的 Content-Disposition 由此决定
 * @param updatedAt   最近一次变更时间
 */
public record FileInfoResponse(String path, String name, long size, String contentType,
                               boolean inline, LocalDateTime updatedAt) {
}
