package com.dailysync.dto;

/**
 * 附件上传结果（与 push 的 SyncPushResponse.ItemResult 语义对齐）。
 *
 * @param path    附件在库内的相对路径
 * @param sha256  内容哈希，也是磁盘 blob 的文件名
 * @param size    字节数
 * @param version 变更后的仓库版本号；status=unchanged 时是当前版本（未推进）
 * @param status  stored=已落库并推进版本；unchanged=同路径同内容，未产生任何变更
 */
public record AttachmentUploadResponse(String path, String sha256, long size, long version, String status) {
}
