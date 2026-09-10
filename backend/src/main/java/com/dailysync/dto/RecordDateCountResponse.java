package com.dailysync.dto;

import java.time.LocalDate;

/**
 * 各日记录数（日历打点用）。
 *
 * @param date  记录日期
 * @param count 当天记录条数（不含墓碑）
 */
public record RecordDateCountResponse(LocalDate date, long count) {
}
