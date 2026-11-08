package com.dailysync.service;

import com.dailysync.omofun.OmofunException;
import com.dailysync.omofun.OmofunProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * OmoFun 登录态（M11）：读取桌面客户端 auth-tokens.json，解析账号 UID。
 *
 * <p>token 是 {@code payload.SIGNATURE} 两段结构（非标准三段 JWT），10 年有效；
 * uid 在第一段 payload 里，仅用于页面展示，不做签名校验（服务端才是最终校验方）。
 * 桌面客户端会自己续期这个文件；服务器上是一份拷贝，若 token 失效，需要重新
 * 从客户端拷一次（或等客户端刷新后同步）。
 */
@Service
@RequiredArgsConstructor
public class OmofunTokenService {

    private final OmofunProperties properties;
    private final ObjectMapper objectMapper;

    /** 读取 accessToken；文件缺失/格式不对时抛出可展示的中文错误。 */
    public String readAccessToken() {
        Path path = Path.of(properties.getAuthTokensPath());
        try {
            JsonNode json = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            JsonNode token = json.get("accessToken");
            if (token == null || !token.isTextual() || token.asText().isBlank()) {
                throw new OmofunException(
                        "auth-tokens.json 中没有 accessToken（请打开一次 OmoFun 客户端重新登录）");
            }
            return token.asText();
        } catch (IOException e) {
            throw new OmofunException("读取 token 文件失败: " + path
                    + "（请确认已把 OmoFun 登录态挂载到该路径）", e);
        }
    }

    /** 页面展示用：token 缺失/解析失败返回 null，不影响总览其余数据。 */
    public Long decodeAccountUidSafe() {
        try {
            return decodeAccountUid(readAccessToken());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Long decodeAccountUid(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] segments = token.split("\\.");
        for (int i = 0; i < Math.min(2, segments.length); i++) {
            JsonNode json = parseSegment(segments[i]);
            if (json != null && json.has("uid") && json.get("uid").isNumber()) {
                return json.get("uid").asLong();
            }
        }
        return null;
    }

    private JsonNode parseSegment(String segment) {
        try {
            // token 段是无 padding 的 base64url；归一化成标准 base64 后补齐再解
            String normalized = segment.replace('-', '+').replace('_', '/');
            int padding = (4 - normalized.length() % 4) % 4;
            normalized = normalized + "=".repeat(padding);
            return objectMapper.readTree(Base64.getDecoder().decode(normalized));
        } catch (Exception e) {
            return null;
        }
    }
}
