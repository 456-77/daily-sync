package com.dailysync.service;

import com.dailysync.common.BizException;
import com.dailysync.entity.OmofunSetting;
import com.dailysync.omofun.OmofunProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * OmoFun 内置调度器（M11，替代旧版的 Windows 计划任务 / Rust 内置 loop）。
 *
 * <p>每 30 秒对表一次：到点且当天未触发 → 记日期 → 起任务。按日期幂等，
 * 因此 Spring 重启、多一次 tick 都不会重复跑；失败也不会在同一天重试（与旧版一致）。
 * 单实例部署假设（compose 只有一个 daily-sync 容器），多实例需要换成数据库锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OmofunScheduler {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final OmofunProperties properties;
    private final OmofunSettingsService settings;
    private final OmofunJobService jobs;

    @Scheduled(fixedDelayString = "${daily-sync.omofun.scheduler-tick-ms:30000}")
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            OmofunSetting setting = settings.load();
            if (setting.getScheduleEnabled() == null || setting.getScheduleEnabled() == 0) {
                return;
            }
            if (jobs.isRunning()) {
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            String today = now.toLocalDate().toString();
            String hm = now.format(HH_MM);

            if (hm.equals(setting.getPollTime()) && !today.equals(setting.getLastPollDate())) {
                settings.markPollTriggered(today);
                startQuietly("poll");
            }
            if (hm.equals(setting.getKeepaliveTime()) && keepaliveDue(setting, now.toLocalDate())) {
                settings.markKeepaliveTriggered(today);
                startQuietly("keepalive");
            }
        } catch (RuntimeException e) {
            // 调度线程绝不能因为一次 DB 抖动挂掉
            log.warn("[omofun] 调度器对表失败: {}", e.getMessage());
        }
    }

    private void startQuietly(String kind) {
        try {
            jobs.start(kind, List.of());
        } catch (BizException e) {
            // 已有任务在跑：本轮放弃，日期已记（与旧版 spawn_job 静默返回一致）
            log.info("[omofun] 调度触发 {} 未执行: {}", kind, e.getMessage());
        }
    }

    private boolean keepaliveDue(OmofunSetting setting, LocalDate today) {
        String last = setting.getLastKeepaliveDate();
        if (last == null || last.isBlank()) {
            return true; // 从未保活过 → 到点就跑
        }
        try {
            LocalDate lastDate = LocalDate.parse(last);
            int everyDays = setting.getKeepaliveEveryDays() == null
                    ? 7 : setting.getKeepaliveEveryDays();
            return ChronoUnit.DAYS.between(lastDate, today) >= everyDays;
        } catch (DateTimeParseException e) {
            return true;
        }
    }
}
