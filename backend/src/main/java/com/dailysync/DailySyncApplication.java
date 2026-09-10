package com.dailysync;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * daily-sync 服务端入口：Spring Boot 3 + MyBatis-Plus。
 * 配置经环境变量注入（DB_PASSWORD / JWT_SECRET / INVITE_CODE），见 application.yml 与 .env.example。
 */
@SpringBootApplication
@MapperScan("com.dailysync.mapper")
public class DailySyncApplication {
    public static void main(String[] args) {
        SpringApplication.run(DailySyncApplication.class, args);
    }
}
