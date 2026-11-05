package com.dailysync.service;

import com.dailysync.common.BizException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 门户免登录票据（M9）：quick-note 用本机保存的账号密码换一张一次性短 TTL 票据，
 * 拼进门户页 URL 的 #fragment 带给浏览器（fragment 不会进服务器访问日志），
 * 门户页 JS 再拿票据换正式令牌对，实现应用 → 浏览器的免登录跳转。
 *
 * <p>刻意放内存而不是数据库：票据只活 2 分钟、一次性使用，重启丢一批的代价
 * 是用户从应用里重新点一次；换来的是零 schema 变更、零迁移步骤。明文不落任何
 * 存储，map 里只存 SHA-256 哈希——与 refresh token「只存哈希」同一约定。
 */
@Service
public class PortalTicketService {

    /** 票据有效期：覆盖「签发 → 系统拉起浏览器 → 页面兑换」的全链路，2 分钟足够 */
    private static final long TTL_SECONDS = 120;
    /** 容量上限：auth 限流桶（10/分钟/IP）已挡住大头，这里只是第二道保险防堆积 */
    private static final int MAX_TICKETS = 64;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> tickets = new ConcurrentHashMap<>();

    private record Entry(long userId, LocalDateTime expiresAt) {}

    /** 凭据校验已由 AuthService 完成（与登录同口径），这里只管签发。明文只出现这一次。 */
    public String issue(long userId) {
        sweep();
        if (tickets.size() >= MAX_TICKETS) {
            throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "票据签发过于频繁，请稍后再试");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = HexFormat.of().formatHex(bytes);
        tickets.put(sha256Hex(ticket), new Entry(userId, LocalDateTime.now().plusSeconds(TTL_SECONDS)));
        return ticket;
    }

    /**
     * 一次性兑换：{@code map.remove} 原子认领，同一票据并发兑换只有一方成功。
     * 不存在 / 已过期 / 已使用统一返回 null，调用方回同一句 401——
     * 不区分失败原因，不给拿旧票据重放探测的人留任何信息差。
     */
    public Long exchange(String ticket) {
        Entry entry = tickets.remove(sha256Hex(ticket));
        if (entry == null || entry.expiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        return entry.userId();
    }

    /** 签发前顺带清扫过期项：票据量小（上限 64），不值得起定时任务 */
    private void sweep() {
        LocalDateTime now = LocalDateTime.now();
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
