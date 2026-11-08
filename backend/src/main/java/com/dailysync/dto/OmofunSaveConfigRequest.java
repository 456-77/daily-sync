package com.dailysync.dto;

/**
 * 保存运行参数请求。前端会把整个 config 对象发回来（含只读路径），
 * 这里只接收可改的两段；Jackson 默认忽略未知字段，不会因多余路径字段报错。
 */
public record OmofunSaveConfigRequest(
        OmofunKeepaliveConfig keepalive,
        OmofunScheduleConfig schedule) {
}
