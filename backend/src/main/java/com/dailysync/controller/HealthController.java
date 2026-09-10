package com.dailysync.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查（无鉴权），供部署探活 / 启动脚本确认服务就绪。
 */
@RestController
public class HealthController {

    /** 恒返回 {"status":"ok"}，只要能响应即代表进程存活。 */
    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
