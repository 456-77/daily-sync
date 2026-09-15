package com.dailysync.dto;

/**
 * 附件墓碑（删除）结果，幂等：对未知路径或不存在的附件删除是无操作。
 *
 * @param path    附件在库内的相对路径
 * @param version 版本号；status=unchanged 时是当前版本（未推进）
 * @param status  deleted=已置墓碑并推进版本；unchanged=本来就没有，未产生变更
 */
public record AttachmentDeleteResponse(String path, long version, String status) {
}
