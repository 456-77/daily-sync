package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.dto.DailyRecordResponse;
import com.dailysync.dto.RecordDateCountResponse;
import com.dailysync.service.RecordQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 日记查询接口（JWT 保护）：前端按日浏览已同步的日记。
 * 数据只能看不能改——写入只能走同步接口（M5.1 起同样用 JWT 鉴权）。
 * @id：仓库id
 */
@RestController
@RequestMapping("/api/v1/vaults/{id}/records")
@RequiredArgsConstructor
public class RecordController {

    private final RecordQueryService recordQueryService;

    /**
     * 查询某一天的全部记录（含内容，按路径排序）。
     *
     * <p>错误：400 日期格式非法（需 YYYY-MM-DD）；404 仓库不存在。
     */
    @GetMapping
    public ApiResponse<List<DailyRecordResponse>> listByDate(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(recordQueryService.listByDate(UserContext.userId(), id, date));
    }

    /**
     * 各日记录数（日历打点）。from/to 缺省为最近 180 天，返回按日期升序。
     *
     * <p>错误：404 仓库不存在。
     */
    @GetMapping("/dates")
    public ApiResponse<List<RecordDateCountResponse>> dateCounts(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(recordQueryService.dateCounts(UserContext.userId(), id, from, to));
    }
}
