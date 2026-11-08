package com.dailysync.omofun;

import com.google.protobuf.Timestamp;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * OmoFun 存档的时间格式（M11）。
 *
 * <p>为什么固定成毫秒精度 + 字面 Z：这些字符串既落 MySQL VARCHAR，又参与字典序比较
 * （空闲阈值、mark_missing_as_reset）。Java 的 Instant.toString 在整秒时会省略小数位，
 * "…00Z" 与 "…00.500Z" 的字典序会反过来；统一成 {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}
 * 后长度恒定、比较语义与 Rust 版一致，也不受服务器时区影响。
 */
public final class OmofunTime {

    private static final DateTimeFormatter ISO_MILLIS_UTC =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private OmofunTime() {
    }

    public static String now() {
        return format(Instant.now());
    }

    public static String minusDays(int days) {
        return format(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    public static String format(Instant instant) {
        return ISO_MILLIS_UTC.format(instant);
    }

    /** protobuf Timestamp → 固定格式字符串；nanos 越界时按毫秒截断（服务端不会发这种值）。 */
    public static String fromTimestamp(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        int nanos = Math.max(0, Math.min(999_999_999, ts.getNanos()));
        return format(Instant.ofEpochSecond(ts.getSeconds(), nanos));
    }
}
