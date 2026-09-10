package com.dailysync.dto;

import java.time.LocalDateTime;

/**
 * 周记元数据（日历周记列打点用，不含正文）。
 *
 * <p>周记的 record_date 为 NULL（文件名首段是 2026-W37 这类周标识，不是日期），
 * 进不了按日查询，因此单独按文件名首段识别。点击某一周时再用 path 取全文。
 *
 * @param week      ISO 周标识，形如 2026-W37
 * @param path      库内相对路径
 * @param updatedAt 最后更新时间
 */
public record WeeklyRecordResponse(String week, String path, LocalDateTime updatedAt) {
}
