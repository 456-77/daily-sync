package com.dailysync.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 客户端 IP 解析：直连取 remoteAddr；部署在反向代理后（M6）改取
 * X-Forwarded-For 首段（代理追加的链路里第一个才是真实客户端）。
 *
 * <p>是否信任 X-Forwarded-For 由 {@code daily-sync.behind-proxy} 开关控制
 * （默认 false）：该头客户端可随意伪造，服务直连公网时若盲信，攻击者
 * 可借伪造头绕过按 IP 的限流——只有流量确定经过自家代理时才该打开。
 */
@Component
public class ClientIp {

    @Value("${daily-sync.behind-proxy:false}")
    private boolean behindProxy;

    /** 解析本次请求的客户端 IP（限流与审计日志共用）。 */
    public String of(HttpServletRequest request) {
        if (behindProxy) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
