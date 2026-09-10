package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.dto.AuditLogResponse;
import com.dailysync.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计日志查询接口（JWT 保护，只能看自己的）。
 * 记录注册、登录成败、仓库自动创建等安全事件，只增不改；
 * 写入点分布在 AuthService / VaultService。
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    /**
     * 当前用户的审计日志，新→旧。
     *
     * <p>参数：before 为上一页最后一条的 id（首页不传）；limit 每页条数，
     * 自动收敛到 1~100（默认 50）。返回条数 &lt; limit 即已到末页。
     */
    @GetMapping
    public ApiResponse<List<AuditLogResponse>> list(
            @RequestParam(name = "before", required = false) Long before,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ApiResponse.ok(auditService.list(UserContext.userId(), before, limit));
    }
}
