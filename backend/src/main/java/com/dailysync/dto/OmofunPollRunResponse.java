package com.dailysync.dto;

import com.dailysync.entity.OmofunPollRun;

/** OmoFun 存档运行记录响应（总览页「最近运行」表格）。 */
public record OmofunPollRunResponse(
        Long id,
        String startedAt,
        String finishedAt,
        boolean ok,
        Integer pages,
        Integer serverEntries,
        Integer newEntries,
        Integer updatedEntries,
        Integer backfilledEntries,
        Integer resetDetected,
        String error) {

    public static OmofunPollRunResponse from(OmofunPollRun r) {
        return new OmofunPollRunResponse(r.getId(), r.getStartedAt(), r.getFinishedAt(),
                r.getOk() != null && r.getOk() != 0, r.getPages(), r.getServerEntries(),
                r.getNewEntries(), r.getUpdatedEntries(), r.getBackfilledEntries(),
                r.getResetDetected(), r.getError());
    }
}
