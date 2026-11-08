package com.dailysync.service;

import com.dailysync.common.BizException;
import com.dailysync.dto.OmofunJobResponse;
import com.dailysync.omofun.OmofunException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * OmoFun 后台任务（M11）：单飞执行器 + 环形日志 + 最近结果。
 *
 * <p>与旧 axum 版一致：同一时刻只允许一个 poll/keepalive/restore；前端每 600ms 轮询
 * get_job 拿进度。任务跑在独立单线程池里，不占 Tomcat 工作线程；日志只留最近 400 条，
 * 页面展示最新 80 条（新→旧，与 Rust 版一致）。
 */
@Service
@RequiredArgsConstructor
public class OmofunJobService {

    private final OmofunArchiveService archive;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "omofun-job");
        thread.setDaemon(true);
        return thread;
    });

    private final Deque<String> logs = new ArrayDeque<>();

    private volatile boolean running;
    private volatile String name = "";
    private volatile String lastResult;

    /** 启动任务；已有任务时抛 409（前端 alert 该消息）。 */
    public synchronized void start(String kind, List<String> keys) {
        if (!"poll".equals(kind) && !"keepalive".equals(kind) && !"restore".equals(kind)) {
            throw new BizException(HttpStatus.BAD_REQUEST, "未知任务类型: " + kind);
        }
        if (running) {
            throw new BizException(HttpStatus.CONFLICT, "已有任务在运行");
        }
        running = true;
        name = kind;
        lastResult = null;
        synchronized (logs) {
            logs.clear();
        }

        try {
            executor.submit(() -> {
                try {
                    Consumer<String> sink = this::appendLog;
                    String result = switch (kind) {
                        case "keepalive" -> archive.keepalive(sink);
                        case "restore" -> archive.restore(keys, sink);
                        default -> archive.poll(sink);
                    };
                    lastResult = result;
                } catch (RuntimeException e) {
                    lastResult = "失败：" + errorText(e);
                } finally {
                    running = false;
                }
            });
        } catch (RejectedExecutionException e) {
            running = false;
            throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "任务执行器已关闭");
        }
    }

    public OmofunJobResponse job() {
        List<String> view = new ArrayList<>();
        synchronized (logs) {
            Iterator<String> it = logs.descendingIterator();
            while (it.hasNext() && view.size() < 80) {
                view.add(it.next());
            }
        }
        return new OmofunJobResponse(running, name, lastResult, view);
    }

    public boolean isRunning() {
        return running;
    }

    public String lastResult() {
        return lastResult;
    }

    public void appendLog(String message) {
        synchronized (logs) {
            logs.addLast(message);
            while (logs.size() > 400) {
                logs.removeFirst();
            }
        }
    }

    private static String errorText(Throwable e) {
        String message = e.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
