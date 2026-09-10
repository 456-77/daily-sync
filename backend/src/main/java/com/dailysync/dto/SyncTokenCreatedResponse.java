package com.dailysync.dto;

import java.time.LocalDateTime;

/**
 * 同步令牌签发响应。
 *
 * @param token 明文令牌（dst_ + 64 位十六进制，共 68 字符）——只在本次响应出现一次，
 *              之后库里只有哈希，丢失只能撤销重签
 */
public record SyncTokenCreatedResponse(Long id, String name, String token, LocalDateTime createdAt) {
}
