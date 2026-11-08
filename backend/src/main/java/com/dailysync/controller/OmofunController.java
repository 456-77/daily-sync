package com.dailysync.controller;

import com.dailysync.common.ApiResponse;
import com.dailysync.common.BizException;
import com.dailysync.dto.OmofunConfigResponse;
import com.dailysync.dto.OmofunEntryResponse;
import com.dailysync.dto.OmofunJobResponse;
import com.dailysync.dto.OmofunMigrateResponse;
import com.dailysync.dto.OmofunOverviewResponse;
import com.dailysync.dto.OmofunProbeResponse;
import com.dailysync.dto.OmofunSaveConfigRequest;
import com.dailysync.dto.OmofunSchedulerResponse;
import com.dailysync.dto.OmofunStartJobRequest;
import com.dailysync.omofun.OmofunProperties;
import com.dailysync.service.OmofunArchiveService;
import com.dailysync.service.OmofunJobService;
import com.dailysync.service.OmofunLegacyMigrationService;
import com.dailysync.service.OmofunQueryService;
import com.dailysync.service.OmofunReportService;
import com.dailysync.service.OmofunSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * OmoFun 播放历史存档接口（M11）。
 *
 * <p>权限：整个 {@code /api/v1/omofun/**} 由 AuthInterceptor 要求 ADMIN——存档是
 * 单个 OmoFun 账号的全局数据（手机+电脑共用），不能让普通 daily-sync 用户看到。
 *
 * <p>接口形状沿用旧 Rust 网页版（overview/entries/job/config/scheduler/start-job），
 * 只是改成 daily-sync 的 REST 命名与 ApiResponse 信封；前端 api-adapter 负责解包。
 * report/export 返回原始 HTML/JSON（浏览器 Blob 打开），不套信封。
 */
@RestController
@RequestMapping("/api/v1/omofun")
@RequiredArgsConstructor
public class OmofunController {

    private final OmofunProperties properties;
    private final OmofunQueryService queryService;
    private final OmofunJobService jobService;
    private final OmofunSettingsService settingsService;
    private final OmofunArchiveService archiveService;
    private final OmofunReportService reportService;
    private final OmofunLegacyMigrationService migrationService;

    @GetMapping("/overview")
    public ApiResponse<OmofunOverviewResponse> overview() {
        ensureEnabled();
        return ApiResponse.ok(queryService.overview());
    }

    @GetMapping("/entries")
    public ApiResponse<List<OmofunEntryResponse>> entries() {
        ensureEnabled();
        return ApiResponse.ok(queryService.entries());
    }

    @GetMapping("/restore-candidates")
    public ApiResponse<List<OmofunEntryResponse>> restoreCandidates() {
        ensureEnabled();
        return ApiResponse.ok(queryService.restoreCandidates());
    }

    @GetMapping("/job")
    public ApiResponse<OmofunJobResponse> job() {
        ensureEnabled();
        return ApiResponse.ok(jobService.job());
    }

    @GetMapping("/config")
    public ApiResponse<OmofunConfigResponse> config() {
        ensureEnabled();
        return ApiResponse.ok(settingsService.configResponse());
    }

    @PutMapping("/config")
    public ApiResponse<Void> saveConfig(@RequestBody OmofunSaveConfigRequest request) {
        ensureEnabled();
        settingsService.save(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/scheduler")
    public ApiResponse<OmofunSchedulerResponse> scheduler() {
        ensureEnabled();
        return ApiResponse.ok(settingsService.schedulerResponse());
    }

    /** 启动后台任务（poll / keepalive / restore）；已有任务时 409。 */
    @PostMapping("/jobs")
    public ApiResponse<Void> startJob(@Valid @RequestBody OmofunStartJobRequest request) {
        ensureEnabled();
        jobService.start(request.kind(), request.keys() == null ? List.of() : request.keys());
        return ApiResponse.ok(null);
    }

    /** 传输层自检：DNS/TLS/ALPN/签名/token 全链路只拉 5 条。 */
    @PostMapping("/probe")
    public ApiResponse<OmofunProbeResponse> probe() {
        ensureEnabled();
        return ApiResponse.ok(archiveService.probe());
    }

    /** 旧 SQLite 存档一次性迁移（幂等：entries 补缺，流水/运行记录仅空表导入）。 */
    @PostMapping("/migrate-legacy")
    public ApiResponse<OmofunMigrateResponse> migrateLegacy() {
        ensureEnabled();
        return ApiResponse.ok(migrationService.migrate());
    }

    @GetMapping(value = "/report", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> report() {
        ensureEnabled();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(reportService.generateHtml());
    }

    @GetMapping(value = "/export", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> export() {
        ensureEnabled();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(reportService.exportJson());
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new BizException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OmoFun 存档模块已停用（daily-sync.omofun.enabled=false）");
        }
    }
}
