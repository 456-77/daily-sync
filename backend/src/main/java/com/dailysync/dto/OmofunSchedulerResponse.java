package com.dailysync.dto;

/** 调度器状态（设置页展示用）。 */
public record OmofunSchedulerResponse(
        boolean enabled,
        String pollTime,
        int keepaliveEveryDays,
        String keepaliveTime,
        String lastPollDate,
        String lastKeepaliveDate) {
}
