package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.dto.DailyRecordResponse;
import com.dailysync.dto.RecordDateCountResponse;
import com.dailysync.dto.WeeklyRecordResponse;
import com.dailysync.service.RecordQueryService;
import com.dailysync.service.TodoService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 日记查询与待办写入（JWT 保护）。
 *
 * <p>日记内容只能看不能改——写入走同步接口（插件）或待办写入接口（网页端）。
 *
 * @id：仓库id
 */
@RestController
@RequestMapping("/api/v1/vaults/{id}/records")
@RequiredArgsConstructor
public class RecordController {

    private final RecordQueryService recordQueryService;
    private final TodoService todoService;

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

    /**
     * 周记元数据列表（日历周记列打点）。按周倒序，只返回周标识与路径，
     * 正文点开某一周时再用 {@link #file} 按 path 取，避免列表把全文都拖下来。
     *
     * <p>错误：404 仓库不存在。
     */
    @GetMapping("/weekly")
    public ApiResponse<List<WeeklyRecordResponse>> weekly(@PathVariable Long id) {
        return ApiResponse.ok(recordQueryService.listWeekly(UserContext.userId(), id));
    }

    /**
     * 按库内路径取单个文件全文（周记正文、插件上传的待办数据文件）。
     *
     * <p>path 为库内相对路径，需 URL 编码（含中文与空格）。走唯一键索引。
     * 错误：404 仓库不存在或该路径没有记录。
     */
    @GetMapping("/file")
    public ApiResponse<DailyRecordResponse> file(@PathVariable Long id, @RequestParam String path) {
        return ApiResponse.ok(recordQueryService.getByPath(UserContext.userId(), id, path));
    }

    /**
     * 保存待办数据（网页端增删改后整份提交）。
     *
     * <p>body 是完整的待办快照 JSON（与插件约定的同一格式，条目带 id 与 updatedAt）。
     * 服务端只落库、不合并——条目级合并统一在插件侧实现，避免两处算法漂移：
     * 网页端提交「读到的快照 + 本地改动」，插件下次同步会按 id 合并两侧。
     *
     * <p>错误：400 内容为空或非法 JSON；404 仓库不存在或非本人。
     */
    @PostMapping("/todos")
    public ApiResponse<Void> saveTodos(@PathVariable Long id, @RequestBody String content) {
        todoService.save(UserContext.userId(), id, content);
        return ApiResponse.ok(null);
    }
}
