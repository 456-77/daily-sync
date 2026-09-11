package com.dailysync.controller;

import com.dailysync.auth.UserContext;
import com.dailysync.common.ApiResponse;
import com.dailysync.common.ClientIp;
import com.dailysync.dto.AdminAuditLogResponse;
import com.dailysync.dto.AdminUserResponse;
import com.dailysync.dto.ResetPasswordRequest;
import com.dailysync.dto.UpdateUserStatusRequest;
import com.dailysync.service.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员接口：用户列表与管理、全量审计日志。
 *
 * <p>角色校验不在这一层做——{@link com.dailysync.auth.AuthInterceptor} 对
 * /api/v1/admin/** 统一要求 ADMIN 角色，非管理员到这里之前就已被 403 拦下。
 * 写操作都会记审计，操作者记为当前管理员。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final ClientIp clientIp;

    /** 用户列表（含仓库数、记录数）。 */
    @GetMapping("/users")
    public ApiResponse<List<AdminUserResponse>> users() {
        return ApiResponse.ok(adminService.listUsers());
    }

    /** 启用/禁用用户。错误：400 对自己操作；404 用户不存在。 */
    @PostMapping("/users/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @Valid @RequestBody UpdateUserStatusRequest req,
                                          HttpServletRequest request) {
        adminService.updateStatus(UserContext.userId(), id, req.getStatus(), clientIp.of(request));
        return ApiResponse.ok(null);
    }

    /** 重置用户密码（无需原密码）；改完该用户所有设备需重新登录。 */
    @PostMapping("/users/{id}/password")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @Valid @RequestBody ResetPasswordRequest req,
                                           HttpServletRequest request) {
        adminService.resetPassword(UserContext.userId(), id, req.getNewPassword(), clientIp.of(request));
        return ApiResponse.ok(null);
    }

    /** 删除用户及其全部数据。 */
    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id, HttpServletRequest request) {
        adminService.deleteUser(UserContext.userId(), id, clientIp.of(request));
        return ApiResponse.ok(null);
    }

    /** 全量审计日志（新→旧，可按 id 游标翻页）。 */
    @GetMapping("/audit-logs")
    public ApiResponse<List<AdminAuditLogResponse>> logs(
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(adminService.listAllLogs(before, limit));
    }
}
