package com.dailysync.omofun;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OmoFun 存档模块配置（application.yml 的 {@code daily-sync.omofun.*}）。
 *
 * <p>与 Rust 版 config.toml 的对应关系：路径类字段（token/证书/数据目录）由部署环境
 * 通过 env 提供，不再允许网页端修改；保活范围、空闲阈值、调度时间这类运行参数
 * 存在 MySQL 的 {@code omofun_settings} 单行表里（见 OmofunSettingsService），
 * 因此本类只保留启动时固定的部分。
 */
@Data
@Component
@ConfigurationProperties(prefix = "daily-sync.omofun")
public class OmofunProperties {

    /** 总开关：关掉后调度器不跑、接口返回 503（保留已存档数据的只读查询能力可后续再说） */
    private boolean enabled = true;

    /** OmoFun 桌面客户端登录态文件（%APPDATA%\omofun\auth-tokens.json 的服务器副本） */
    private String authTokensPath = "./data/omofun/auth-tokens.json";

    /** 客户端内置的 server.crt：X.509 v1 自签证书，握手后按 DER 精确锁定 */
    private String rootCertPath = "./data/omofun/server.crt";

    /** 数据目录：报告/导出/旧 SQLite 都放这里 */
    private String dataDir = "./data/omofun";

    /** 桌面客户端本地缓存 watch-history.json（服务器默认没有，skip-backfill=true） */
    private String desktopHistoryPath = "";

    /** 服务器版默认跳过桌面缓存回填（文件不存在时回填本来就是空操作） */
    private boolean skipBackfill = true;

    /** 旧 Rust/Node 版 SQLite 存档路径：只供 migrate-legacy 读取 */
    private String legacySqlitePath = "./data/omofun/omofun_history.db";

    /** 每页拉取条数（与客户端一致） */
    private int pageSize = 20;

    /** 分页上限：防止服务端游标异常时无限翻页 */
    private int maxPages = 100;

    /** 翻页间隔，降低被风控概率 */
    private long pageDelayMs = 300;

    /** gRPC 单次请求超时 */
    private long requestTimeoutMs = 15000;

    /** 端点 TCP 探测超时 */
    private long probeTimeoutMs = 3000;

    /** 内置调度器对表间隔（毫秒） */
    private long schedulerTickMs = 30000;
}
