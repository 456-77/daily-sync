package com.dailysync.dto;

import java.util.List;

/** OmoFun 存档总览响应（统计 + 正在追 + 最近运行）。 */
public record OmofunOverviewResponse(
        Long accountUid,
        int total,
        int watching,
        int done,
        int paused,
        int archived,
        List<OmofunEntryResponse> recent,
        List<OmofunPollRunResponse> runs) {
}
