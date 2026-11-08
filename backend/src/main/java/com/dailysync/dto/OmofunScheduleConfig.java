package com.dailysync.dto;

/** 内置调度参数（与原 Rust config.toml 的 schedule 段同名同义）。 */
public record OmofunScheduleConfig(
        boolean enabled,
        String pollTime,
        int keepaliveEveryDays,
        String keepaliveTime) {
}
