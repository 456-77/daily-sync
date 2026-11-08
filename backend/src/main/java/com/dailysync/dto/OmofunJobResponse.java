package com.dailysync.dto;

import java.util.List;

/** 后台任务状态（前端每 600ms 轮询一次）。logs 为最新 80 条、新→旧。 */
public record OmofunJobResponse(
        boolean running,
        String name,
        String lastResult,
        List<String> logs) {
}
