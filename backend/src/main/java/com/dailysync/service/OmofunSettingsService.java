package com.dailysync.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dailysync.common.BizException;
import com.dailysync.dto.OmofunConfigResponse;
import com.dailysync.dto.OmofunKeepaliveConfig;
import com.dailysync.dto.OmofunSaveConfigRequest;
import com.dailysync.dto.OmofunScheduleConfig;
import com.dailysync.dto.OmofunSchedulerResponse;
import com.dailysync.entity.OmofunSetting;
import com.dailysync.mapper.OmofunSettingMapper;
import com.dailysync.omofun.OmofunProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * OmoFun 存档运行参数（M11）：单行表 omofun_settings 的读写 + 校验。
 *
 * <p>这些参数就是旧 Rust 版 config.toml 里网页端可改的那部分（保活范围/阈值/上限/限速、
 * 调度开关与时间、上次触发日期）。token、证书、数据目录属于部署环境，留在
 * {@link OmofunProperties}，网页端只展示。
 */
@Service
@RequiredArgsConstructor
public class OmofunSettingsService {

    private static final Pattern HH_MM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final int SETTINGS_ID = 1;

    private final OmofunSettingMapper settingMapper;
    private final OmofunProperties properties;
    private final ObjectMapper objectMapper;

    /** 读取单行配置；缺失时按旧版默认值补一行（schema.sql 正常情况下已经插入）。 */
    public synchronized OmofunSetting load() {
        OmofunSetting row = settingMapper.selectById(SETTINGS_ID);
        if (row != null) {
            return row;
        }
        OmofunSetting fresh = new OmofunSetting();
        fresh.setId(SETTINGS_ID);
        fresh.setKeepaliveScope("watching");
        fresh.setKeepaliveIntervalDays(7);
        fresh.setKeepaliveMinIdleDays(5);
        fresh.setKeepaliveMaxWrites(100);
        fresh.setKeepaliveDelayMs(500L);
        fresh.setKeepaliveWhitelist("[]");
        fresh.setScheduleEnabled(1);
        fresh.setPollTime("09:00");
        fresh.setKeepaliveEveryDays(7);
        fresh.setKeepaliveTime("09:20");
        fresh.setUpdatedAt(LocalDateTime.now());
        settingMapper.insert(fresh);
        return fresh;
    }

    /** 网页端配置视图：只读部署参数 + 可改运行参数。 */
    public OmofunConfigResponse configResponse() {
        OmofunSetting s = load();
        return new OmofunConfigResponse(
                properties.getDataDir(),
                properties.getRootCertPath(),
                properties.getAuthTokensPath(),
                properties.getPageSize(),
                properties.getMaxPages(),
                properties.getPageDelayMs(),
                properties.getRequestTimeoutMs(),
                properties.getProbeTimeoutMs(),
                properties.isSkipBackfill(),
                keepaliveConfig(s),
                scheduleConfig(s));
    }

    public OmofunKeepaliveConfig keepaliveConfig() {
        return keepaliveConfig(load());
    }

    public OmofunScheduleConfig scheduleConfig() {
        return scheduleConfig(load());
    }

    public OmofunSchedulerResponse schedulerResponse() {
        OmofunSetting s = load();
        return new OmofunSchedulerResponse(
                s.getScheduleEnabled() != null && s.getScheduleEnabled() != 0,
                s.getPollTime(), s.getKeepaliveEveryDays(), s.getKeepaliveTime(),
                s.getLastPollDate(), s.getLastKeepaliveDate());
    }

    /**
     * 保存网页端可改参数。前端会把整个 config 对象（含只读路径）发回来，
     * DTO 只接了 keepalive / schedule 两段，多余字段被 Jackson 忽略。
     */
    public synchronized void save(OmofunSaveConfigRequest req) {
        OmofunSetting s = load();
        if (req.keepalive() != null) {
            OmofunKeepaliveConfig k = req.keepalive();
            String scope = k.scope() == null ? "watching" : k.scope();
            if (!"watching".equals(scope) && !"all".equals(scope) && !"whitelist".equals(scope)) {
                throw new BizException(HttpStatus.BAD_REQUEST, "保活范围只能是 watching / all / whitelist");
            }
            if (k.minIdleDays() < 0 || k.minIdleDays() > 365) {
                throw new BizException(HttpStatus.BAD_REQUEST, "空闲阈值需在 0~365 天之间");
            }
            if (k.maxWrites() < 1 || k.maxWrites() > 1000) {
                throw new BizException(HttpStatus.BAD_REQUEST, "单轮上限需在 1~1000 条之间");
            }
            if (k.intervalDays() < 1 || k.intervalDays() > 365) {
                throw new BizException(HttpStatus.BAD_REQUEST, "保活间隔需在 1~365 天之间");
            }
            if (k.delayMs() < 0 || k.delayMs() > 60_000) {
                throw new BizException(HttpStatus.BAD_REQUEST, "请求间隔需在 0~60000 毫秒之间");
            }
            s.setKeepaliveScope(scope);
            s.setKeepaliveIntervalDays(k.intervalDays());
            s.setKeepaliveMinIdleDays(k.minIdleDays());
            s.setKeepaliveMaxWrites(k.maxWrites());
            s.setKeepaliveDelayMs(k.delayMs());
            s.setKeepaliveWhitelist(writeWhitelist(k.whitelist()));
        }
        if (req.schedule() != null) {
            OmofunScheduleConfig sc = req.schedule();
            if (sc.pollTime() == null || !HH_MM.matcher(sc.pollTime()).matches()) {
                throw new BizException(HttpStatus.BAD_REQUEST, "存档时间必须是 HH:MM");
            }
            if (sc.keepaliveTime() == null || !HH_MM.matcher(sc.keepaliveTime()).matches()) {
                throw new BizException(HttpStatus.BAD_REQUEST, "保活时间必须是 HH:MM");
            }
            if (sc.keepaliveEveryDays() < 1 || sc.keepaliveEveryDays() > 365) {
                throw new BizException(HttpStatus.BAD_REQUEST, "保活周期需在 1~365 天之间");
            }
            s.setScheduleEnabled(sc.enabled() ? 1 : 0);
            s.setPollTime(sc.pollTime());
            s.setKeepaliveEveryDays(sc.keepaliveEveryDays());
            s.setKeepaliveTime(sc.keepaliveTime());
        }
        s.setUpdatedAt(LocalDateTime.now());
        settingMapper.updateById(s);
    }

    /** 调度器触发后立刻记日期（与旧版一致：先记再跑，失败也不在同一天重试）。 */
    public void markPollTriggered(String today) {
        settingMapper.update(null, new LambdaUpdateWrapper<OmofunSetting>()
                .eq(OmofunSetting::getId, SETTINGS_ID)
                .set(OmofunSetting::getLastPollDate, today)
                .set(OmofunSetting::getUpdatedAt, LocalDateTime.now()));
    }

    public void markKeepaliveTriggered(String today) {
        settingMapper.update(null, new LambdaUpdateWrapper<OmofunSetting>()
                .eq(OmofunSetting::getId, SETTINGS_ID)
                .set(OmofunSetting::getLastKeepaliveDate, today)
                .set(OmofunSetting::getUpdatedAt, LocalDateTime.now()));
    }

    public List<String> whitelist(OmofunSetting s) {
        String raw = s.getKeepaliveWhitelist();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // 手工改库写坏了按空列表处理，不阻塞保活（旧版也是尽力而为）
            return List.of();
        }
    }

    private OmofunKeepaliveConfig keepaliveConfig(OmofunSetting s) {
        return new OmofunKeepaliveConfig(
                s.getKeepaliveScope(),
                s.getKeepaliveIntervalDays() == null ? 7 : s.getKeepaliveIntervalDays(),
                s.getKeepaliveMinIdleDays() == null ? 5 : s.getKeepaliveMinIdleDays(),
                s.getKeepaliveMaxWrites() == null ? 100 : s.getKeepaliveMaxWrites(),
                s.getKeepaliveDelayMs() == null ? 500L : s.getKeepaliveDelayMs(),
                whitelist(s));
    }

    private OmofunScheduleConfig scheduleConfig(OmofunSetting s) {
        return new OmofunScheduleConfig(
                s.getScheduleEnabled() != null && s.getScheduleEnabled() != 0,
                s.getPollTime(),
                s.getKeepaliveEveryDays() == null ? 7 : s.getKeepaliveEveryDays(),
                s.getKeepaliveTime());
    }

    private String writeWhitelist(List<String> whitelist) {
        List<String> safe = whitelist == null ? new ArrayList<>() : whitelist;
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (Exception e) {
            return "[]";
        }
    }
}
