package com.dailysync.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 通用 Bean 配置。 */
@Configuration
public class AppConfig {

    /** 密码哈希用 BCrypt（自带盐，校验走 matches），不引入完整 Spring Security 过滤链。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
