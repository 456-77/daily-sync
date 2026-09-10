package com.dailysync.dto;

import java.time.LocalDateTime;

/**
 * 日记内容响应（按日查询）。
 *
 * @param path 库内相对路径，文件名首段即记录日期
 */
public record DailyRecordResponse(Long id, String path, String content, long version,
                                  LocalDateTime updatedAt) {
}
