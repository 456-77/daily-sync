package com.dailysync.dto;

import java.util.List;

/** 保活参数（与原 Rust config.toml 的 keepalive 段同名同义）。 */
public record OmofunKeepaliveConfig(
        String scope,
        int intervalDays,
        int minIdleDays,
        int maxWrites,
        long delayMs,
        List<String> whitelist) {
}
