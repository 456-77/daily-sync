package com.dailysync.dto;

/**
 * 门户票据兑换成功后的会话（M9）。比 {@link TokenResponse} 多带 username：
 * 网页端的登录态结构是 {accessToken, refreshToken, username}，门户页 JS
 * 换到票据后要按**完全相同的键名**写进 localStorage，顶栏才显示得出用户名。
 */
public record PortalSessionResponse(String accessToken, String refreshToken, String username) {
}
