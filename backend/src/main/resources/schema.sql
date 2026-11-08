-- OmoFun 播放历史存档（M11）—— 从独立 Rust 服务迁移进 daily-sync 后的表结构。
--
-- 为什么不用 Flyway：本项目自 M1 起就是「启动前手工/文档 DDL + 运行时 MYBATIS」的约定，
-- 这里沿用同一套。所有语句都是 IF NOT EXISTS / ON DUPLICATE KEY，可随每次启动重复执行，
-- 已有数据不会被触碰（spring.sql.init.mode=always，见 application.yml）。
--
-- 时间列刻意用 VARCHAR 而不是 DATETIME：存档里的 last_server_seen_at / server_updated_at
-- 要跟 Java 生成的 ISO 字符串做字典序比较（mark_missing_as_reset、保活空闲阈值），
-- 统一成固定长度 UTC 字符串后比较语义与 Rust 版完全一致，也不受 MySQL 时区转换影响。

CREATE TABLE IF NOT EXISTS omofun_entries (
  entry_key VARCHAR(128) NOT NULL,
  content_type VARCHAR(32) NOT NULL,
  content_id BIGINT NOT NULL,
  content_key VARCHAR(255) NULL,
  title TEXT NULL,
  cover_url TEXT NULL,
  last_episode_id VARCHAR(128) NULL,
  last_episode_index INT NULL,
  last_line_id VARCHAR(128) NULL,
  player_name VARCHAR(128) NULL,
  episode_title TEXT NULL,
  progress_seconds BIGINT NULL,
  progress_percent DOUBLE NULL,
  total_duration_seconds BIGINT NULL,
  status INT NULL,
  version BIGINT NULL,
  server_updated_at VARCHAR(32) NULL,
  attributes_json MEDIUMTEXT NULL,
  resource_snapshot_json MEDIUMTEXT NULL,
  source VARCHAR(16) NOT NULL DEFAULT 'server',
  first_seen_at VARCHAR(32) NOT NULL,
  last_seen_at VARCHAR(32) NOT NULL,
  last_server_seen_at VARCHAR(32) NULL,
  server_gone_at VARCHAR(32) NULL,
  originally_watched_at VARCHAR(32) NULL,
  PRIMARY KEY (entry_key),
  KEY idx_omofun_entries_updated (server_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS omofun_progress_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  entry_key VARCHAR(128) NOT NULL,
  seen_at VARCHAR(32) NOT NULL,
  source VARCHAR(16) NOT NULL,
  episode_id VARCHAR(128) NULL,
  episode_index INT NULL,
  line_id VARCHAR(128) NULL,
  progress_seconds BIGINT NULL,
  progress_percent DOUBLE NULL,
  total_duration_seconds BIGINT NULL,
  status INT NULL,
  version BIGINT NULL,
  server_updated_at VARCHAR(32) NULL,
  PRIMARY KEY (id),
  KEY idx_omofun_progress_key (entry_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS omofun_poll_runs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  started_at VARCHAR(32) NOT NULL,
  finished_at VARCHAR(32) NULL,
  ok TINYINT NOT NULL DEFAULT 0,
  pages INT NULL,
  server_entries INT NULL,
  new_entries INT NULL,
  updated_entries INT NULL,
  backfilled_entries INT NULL,
  reset_detected INT NULL,
  error TEXT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 单行配置表：只存网页端可改的保活/调度参数；token、证书、路径属于部署环境，走 env。
CREATE TABLE IF NOT EXISTS omofun_settings (
  id TINYINT NOT NULL,
  keepalive_scope VARCHAR(16) NOT NULL DEFAULT 'watching',
  keepalive_interval_days INT NOT NULL DEFAULT 7,
  keepalive_min_idle_days INT NOT NULL DEFAULT 5,
  keepalive_max_writes INT NOT NULL DEFAULT 100,
  keepalive_delay_ms BIGINT NOT NULL DEFAULT 500,
  keepalive_whitelist TEXT NULL,
  schedule_enabled TINYINT NOT NULL DEFAULT 1,
  poll_time VARCHAR(5) NOT NULL DEFAULT '09:00',
  keepalive_every_days INT NOT NULL DEFAULT 7,
  keepalive_time VARCHAR(5) NOT NULL DEFAULT '09:20',
  last_poll_date VARCHAR(10) NULL,
  last_keepalive_date VARCHAR(10) NULL,
  updated_at DATETIME NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO omofun_settings (id) VALUES (1)
  ON DUPLICATE KEY UPDATE id = id;
