package com.dailysync.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内固定窗口限流器（无第三方依赖，单实例部署够用）。
 *
 * <p>每个 key（如「auth:1.2.3.4」）维护一个窗口：窗口内计数超过限额即拒绝，
 * 窗口翻转（分钟对齐）后自动清零。固定窗口在边界处理论上允许两倍突发
 * （上一窗口尾 + 下一窗口头），对防暴力破解 / 防滥用场景足够，换来实现极简。
 *
 * <p>状态只在内存里：重启清零、多实例各算各的（M6 单实例部署无影响）。
 * key 数量有清理兜底（{@link #sweep}），恶意构造海量 key 也不会撑爆内存。
 */
@Slf4j
@Component
public class RateLimiter {

    /** 单个窗口的计数状态；窗口翻转时整个对象被 compute 替换成新的 */
    private static final class Window {
        final long startSec;
        final AtomicInteger count = new AtomicInteger();

        Window(long startSec) {
            this.startSec = startSec;
        }
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * 尝试占一个名额。
     *
     * @param key           限流维度（IP / 令牌哈希等，由调用方拼好）
     * @param limit         窗口内允许的最大次数
     * @param windowSeconds 窗口长度（秒），当前按 60 使用
     * @return 0 = 放行；&gt;0 = 已限流，值为距窗口重置的秒数（可作 Retry-After 头）
     */
    public int tryAcquire(String key, int limit, int windowSeconds) {
        long nowSec = System.currentTimeMillis() / 1000;
        long windowStart = nowSec - nowSec % windowSeconds;
        // compute 对同一 key 原子执行：窗口过期就整体换新，避免对旧窗口续数
        Window window = windows.compute(key, (k, old) ->
                old == null || old.startSec != windowStart ? new Window(windowStart) : old);
        int used = window.count.incrementAndGet();
        return used <= limit ? 0 : (int) (windowStart + windowSeconds - nowSec);
    }

    /** 每 5 分钟清掉早已过期的窗口，防止 key 随时间无限累积（攻击者换 IP 刷 key 的兜底）。 */
    @Scheduled(fixedDelay = 300_000)
    public void sweep() {
        long nowSec = System.currentTimeMillis() / 1000 - 120; // 留 2 分钟余量，别清掉仍在服务的窗口
        int before = windows.size();
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().startSec < nowSec) {
                it.remove();
            }
        }
        if (windows.size() != before) {
            log.info("限流窗口清理：{} -> {}", before, windows.size());
        }
    }
}
