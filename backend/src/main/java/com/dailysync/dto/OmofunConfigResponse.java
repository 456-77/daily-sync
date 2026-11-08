package com.dailysync.dto;

import java.util.List;

/**
 * 网页端读到的完整配置（只读部分 + 可改部分）。
 *
 * <p>路径字段来自部署环境（application.yml / env），前端只展示不修改；
 * keepalive / schedule 才是 omofun_settings 里可保存的运行参数。
 */
public record OmofunConfigResponse(
        String dataDir,
        String rootCertPath,
        String authTokensPath,
        int pageSize,
        int maxPages,
        long pageDelayMs,
        long requestTimeoutMs,
        long probeTimeoutMs,
        boolean skipBackfill,
        OmofunKeepaliveConfig keepalive,
        OmofunScheduleConfig schedule) {
}
