package com.dailysync.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 记录索引条目（左侧文件列表用，不含正文）。
 *
 * <p>列表要显示的是「文件名」而不是日期聚合，所以逐个文件返回路径与
 * 从文件名首段解析出的日期；recordDate 为 null 表示不是按日期命名的文件
 * （周记等），前端据此分到「周记」分组。
 *
 * @param id         记录 id
 * @param path       库内相对路径，如 diary/2026-09-14 OceanBase 部署.md
 * @param recordDate 从文件名首段解析的日期；非日期命名的文件为 null
 * @param updatedAt  最后更新时间
 */
public record RecordIndexResponse(Long id, String path, LocalDate recordDate, LocalDateTime updatedAt) {
}
