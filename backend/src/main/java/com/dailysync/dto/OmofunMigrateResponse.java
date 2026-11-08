package com.dailysync.dto;

/** 旧 SQLite 存档迁移结果计数。 */
public record OmofunMigrateResponse(long entries, long progressLog, long runs) {
}
