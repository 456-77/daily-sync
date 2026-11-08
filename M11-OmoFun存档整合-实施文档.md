# M11 — OmoFun 播放历史存档并入 daily-sync 实施文档

> 状态：**已部署上线**（2026-11-08，commit `17432ab`，Actions run `42477328161`，30m07s 成功）
>
> 目标：把原来独立的 Rust 网页服务（`omofun-archiver` axum + SQLite + 独立密码登录 +
> 8082 端口）整体并入 daily-sync 的 Spring Boot，复用其账号体系、MySQL、部署流水线与
> nginx 入口。桌面版（Tauri + Rust core）保持不变。

---

## 1. 背景

M10 的拓扑是「nginx 8080 边缘反代 → `/omofun/` 转发到宿主机 systemd 服务
`omofun-archiver:8082`」。这带来四份额外运维成本：

- 两个进程两套部署（Rust 二进制 + systemd vs Docker + compose）；
- 两套鉴权（独立网页密码 vs daily-sync JWT）；
- 两份数据（SQLite 文件 vs MySQL），备份与恢复口径不一致；
- 每次改 UI/接口都要单独编译、scp 二进制、重启 systemd。

M11 把这四件事收敛成一件：OmoFun 存档成为 daily-sync 后端的一个模块，网页入口仍是
`/omofun/`，但由 Spring Boot 直出；数据进 `daily_sync` 库的 `omofun_*` 表；登录复用
daily-sync 账号（仅 ADMIN）；发版走现有 `push master → Actions → compose` 流水线。

**不在本次范围**：桌面版 Tauri/Rust core 不改；`omofun-archiver/server/` 仅保留作历史参考。

---

## 2. 拓扑

### M10（旧）

```
浏览器 ──8080──> nginx ──/──────────> daily-sync:8080 (Spring Boot)
                     └──/omofun/────> host.docker.internal:8082
                                        omofun-archiver (Rust axum)
                                        └─ SQLite /opt/omofun-archiver/data/omofun_history.db
```

### M11（新）

```
浏览器 ──8080──> nginx ──/──> daily-sync:8080 (Spring Boot)
                              ├─ /           门户页
                              ├─ /app/       quick-note SPA
                              ├─ /omofun/    OmoFun 存档页（静态 UI，ADMIN JWT）
                              └─ /api/v1/omofun/**  OmoFun 存档接口
                                   └─ MySQL daily_sync.omofun_*
```

nginx 不再有 `/omofun/` 的单独 location；`/omofun`（无斜杠）由 Spring 的 view controller
重定向到 `/omofun/`，再 forward 到 `static/omofun/index.html`。

---

## 3. 代码位置

| 层 | 文件 |
|---|---|
| 配置 | `backend/src/main/java/com/dailysync/omofun/OmofunProperties.java` |
| 时间格式 | `.../omofun/OmofunTime.java`（固定毫秒 UTC，字典序即时间序） |
| 协议 | `.../omofun/OmofunProto.java`（手写 protobuf 编解码） |
| gRPC/TLS | `.../omofun/OmofunGrpcClient.java`、`OmofunException.java` |
| 控制器 | `com/dailysync/controller/OmofunController.java` |
| 业务 | `com/dailysync/service/OmofunArchiveService.java`（poll/keepalive/restore/probe） |
| 持久层 | `.../service/OmofunStoreService.java`（entries/progress_log/poll_runs） |
| 查询 | `.../service/OmofunQueryService.java` |
| 任务 | `.../service/OmofunJobService.java`（单飞执行器 + 日志） |
| 调度 | `.../service/OmofunScheduler.java`（@Scheduled 30s 对表） |
| 设置 | `.../service/OmofunSettingsService.java`（单行表 + 校验） |
| token | `.../service/OmofunTokenService.java`（auth-tokens.json → accessToken/uid） |
| 报告/导出 | `.../service/OmofunReportService.java`（按需生成 HTML/JSON） |
| 旧数据迁移 | `.../service/OmofunLegacyMigrationService.java`（SQLite → MySQL） |
| DTO | `com/dailysync/dto/Omofun*.java` |
| 实体/Mapper | `com/dailysync/entity/Omofun*.java`、`com/dailysync/mapper/Omofun*Mapper.java` |
| 建表 | `backend/src/main/resources/schema.sql` |
| 前端 | `frontend/omofun/{index.html,app.js,api-adapter.js,style.css}` |
| 手动测试 | `backend/src/test/java/com/dailysync/omofun/OmofunProbeMain.java`、`OmofunE2eTokenMain.java` |

---

## 4. 数据模型（MySQL）

四张表，全部 `utf8mb4`，随每次启动由 `schema.sql`（`IF NOT EXISTS`）幂等创建：

### `omofun_entries` — 存档条目（主键 = 业务 key）

主键 `entry_key`（如 `vod:18057`），与旧 SQLite 完全一致，迁移无需重新编号。核心列：
`content_*`、`title`、`cover_url`、`last_episode_*`、`last_line_id`、`progress_*`、
`status`、`version`、`server_updated_at`、`attributes_json`、`resource_snapshot_json`、
`source`、`first_seen_at`、`last_seen_at`、`last_server_seen_at`、`server_gone_at`、
`originally_watched_at`。

### `omofun_progress_log` — 只增不删的进度流水

`source` 区分 `server` / `backfill` / `restore`；字段为 NULL 表示当时没有该值（进度 0 不落流水）。

### `omofun_poll_runs` — 每次存档（poll）的运行记录

总览页「最近运行」表格的数据源；保活/恢复不记这张表（与旧版一致）。

### `omofun_settings` — 单行运行配置（id=1）

保活范围/间隔/空闲阈值/单轮上限/限速/白名单、调度开关与时间、上次触发日期。
token、证书、数据目录属于部署环境，走 env，不进表。

### 时间列为什么是 VARCHAR

`last_server_seen_at` / `server_updated_at` 要参与字典序比较（空闲阈值、
`mark_missing_as_reset`）。统一为固定长度 UTC ISO：
`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`（`OmofunTime`）。Java 的 `Instant.toString` 在整秒时
会省略小数位，`…00Z` 与 `…00.500Z` 的字典序会反过来，所以不能直接用。

### 写操作安全线（勿回退）

- 只回写存档精确快照，绝不杜撰进度；
- 服务端 version 更大 → 只并入、不覆盖；
- 保活走 `clientVersion=服务端当前 version` 乐观锁，`ABORTED`/details 含 `conflict` 视为冲突；
- 恢复前先 `GetHistory`，服务端还在就只并入；
- 单轮写条数上限 + 请求间隔（settings）；
- entries 物理不删，服务端消失只标 `server_gone_at`。

---

## 5. 接口（全部 ADMIN JWT）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/omofun/overview` | 统计 + 最近 14 条 + 最近 8 次运行 |
| GET | `/api/v1/omofun/entries` | 全部条目 |
| GET | `/api/v1/omofun/restore-candidates` | 恢复候选 |
| GET | `/api/v1/omofun/job` | 任务状态 + 最新 80 条日志 |
| GET | `/api/v1/omofun/config` | 只读部署参数 + 可改运行参数 |
| PUT | `/api/v1/omofun/config` | 保存保活/调度参数 |
| GET | `/api/v1/omofun/scheduler` | 调度开关/时间/上次触发 |
| POST | `/api/v1/omofun/jobs` | `{kind: poll\|keepalive\|restore, keys: []}` |
| POST | `/api/v1/omofun/probe` | 传输层自检（只拉 5 条） |
| POST | `/api/v1/omofun/migrate-legacy` | 旧 SQLite 一次性迁移 |
| GET | `/api/v1/omofun/report` | 完整报告 HTML（裸响应，不套信封） |
| GET | `/api/v1/omofun/export` | 完整导出 JSON（裸响应） |

`AuthInterceptor` 的 `isAdminPath` 现在同时匹配 `/api/v1/admin/` 与
`/api/v1/omofun/`。原因是存档是**单个 OmoFun 账号的全局数据**（手机+电脑共用），
普通 daily-sync 用户不能看到。

前端 `api-adapter.js` 复用 `/app` 网页端的 `localStorage["daily-sync-auth"]`
（`{accessToken, refreshToken, username}`），401 时先用 refreshToken 轮换，失败才弹
登录遮罩。`/omofun/` 与 `/app/` 同源，登录态互通。

---

## 6. 协议层移植要点

### 6.1 TLS

- 服务端证书是 **X.509 v1 自签泛 CN（无 SAN）**，标准链校验必然失败；
- 用 JDK SSLProvider（不引 netty-tcnative 原生库），信任管理器放行链校验，
  但要求**叶子证书 DER 与客户端内置 `server.crt` 逐字节相等**；
- ALPN 必须 h2（前置 nginx 对无 ALPN 连接返回 400）。JDK 9+ 自带 ALPN，
  `GrpcSslContexts.configure(..., SslProvider.JDK)` 会配置；
- 端点：四个 XOR+base64 混淆域名，逐个 DNS + TCP:9091 探测，连接时
  `overrideAuthority(domain)` 固定 SNI。

实测（2026-11-08，本机）：`OmofunProbeMain` 连接成功，拉回 5 条，`nextCursor`/`nextVersion`
正常，UID 2436842。

### 6.2 签名

`x-sign = hex(HMAC-SHA256(key="DM233_SECRET_KEY_V1", path ‖ body ‖ timestamp ‖ nonce))`，
另带 `x-timestamp` / `x-nonce` / `Authorization: Bearer <token>` / `x-client-*` / `x-platform: web`
/ `x-arch: x64`。body 是 `OmofunProto` 手工编码出的字节，**签名与发送内容同源**。

### 6.3 protobuf

不跑 protoc：字段号全部逆向自客户端，手写 `CodedOutputStream`/`CodedInputStream`。
`Struct`（attributes/resourceSnapshot）与 `Timestamp` 直接用 protobuf-java 自带类。
四个 RPC：ListHistories / GetHistory / RecordProgress（DeleteHistory 未用）。

### 6.4 错误语义

- `ABORTED` 或 details 含 `conflict` → 版本冲突，按客户端逻辑重查/并入；
- `UNAUTHENTICATED` → token 失效，提示重新打开 OmoFun 桌面客户端刷新登录；
- 其他 gRPC/证书/token 错误 → 管理员页面显示可读原因（`GlobalExceptionHandler`
  对 `OmofunException` 返回 502 + message）。

---

## 7. 任务与调度

- **单飞**：`OmofunJobService` 单线程执行器，同一时刻只允许一个 poll/keepalive/restore，
  冲突返回 409「已有任务在运行」；日志环形 400 条，页面展示最新 80 条。
- **调度**：`@Scheduled(fixedDelay=30s)` 对表；到 `poll_time` / `keepalive_time` 且当天未触发
  → 先记 `last_*_date` 再起任务（失败也不在同一天重试，与旧版一致）。保活还要求距上次
  ≥ `keepalive_every_days`。
- 单实例部署假设（compose 只有一个 daily-sync 容器）；多实例需要换 DB 锁。

---

## 8. 旧数据迁移

管理员在设置页点「迁移旧 SQLite 存档」，或 `POST /api/v1/omofun/migrate-legacy`：

- 读取 `daily-sync.omofun.legacy-sqlite-path`（容器内默认 `/app/data/omofun/omofun_history.db`）；
- `entries`：逐条 `INSERT IF ABSENT`，已存在不覆盖（新库可能已有更新快照）；
- `progress_log` / `poll_runs`：**仅在目标表为空时整表导入**，避免重复点击造成流水翻倍；
- 旧 RFC3339 时间（可能带纳秒/`+00:00`）统一归一化为固定毫秒 UTC；
- 旧库只读打开，不删不改。

实测迁移：55 条 entries、242 条 progress_log、8 条 poll_runs（本机旧库）。

> 复制旧 SQLite 前先停旧服务；WAL 模式要连 `-wal`/`-shm` 一起拷，或先 checkpoint。

---

## 9. 配置

`application.yml`：

```yaml
spring:
  sql:
    init:
      mode: always          # schema.sql 幂等建表（IF NOT EXISTS）

daily-sync:
  omofun:
    enabled: ${OMOFUN_ENABLED:true}
    auth-tokens-path: ${OMOFUN_AUTH_TOKENS_PATH:./data/omofun/auth-tokens.json}
    root-cert-path: ${OMOFUN_ROOT_CERT_PATH:./data/omofun/server.crt}
    data-dir: ${OMOFUN_DATA_DIR:./data/omofun}
    desktop-history-path: ${OMOFUN_DESKTOP_HISTORY_PATH:}
    skip-backfill: ${OMOFUN_SKIP_BACKFILL:true}
    legacy-sqlite-path: ${OMOFUN_LEGACY_SQLITE_PATH:./data/omofun/omofun_history.db}
    page-size: ${OMOFUN_PAGE_SIZE:20}
    max-pages: ${OMOFUN_MAX_PAGES:100}
    page-delay-ms: ${OMOFUN_PAGE_DELAY_MS:300}
    request-timeout-ms: ${OMOFUN_REQUEST_TIMEOUT_MS:15000}
    probe-timeout-ms: ${OMOFUN_PROBE_TIMEOUT_MS:3000}
    scheduler-tick-ms: ${OMOFUN_SCHEDULER_TICK_MS:30000}
```

容器内相对路径按 `WORKDIR /app` 解析，即 `/app/data/omofun`；宿主机
`/opt/daily-sync/data:/app/data` 已存在（M7 附件卷），**不新增卷**。

---

## 10. 部署步骤

1. **准备服务器数据目录**（首次）：
   ```bash
   ssh -i ~/.ssh/daily_sync_deploy root@120.26.58.22
   mkdir -p /opt/daily-sync/data/omofun
   # 旧服务先停，避免拷到写一半的 SQLite
   systemctl stop omofun-archiver && systemctl disable omofun-archiver
   cp /opt/omofun-archiver/auth-tokens.json /opt/daily-sync/data/omofun/
   cp /opt/omofun-archiver/server.crt          /opt/daily-sync/data/omofun/
   cp /opt/omofun-archiver/data/omofun_history.db* /opt/daily-sync/data/omofun/
   ```
   > 旧服务实际数据目录以服务器为准（`/opt/omofun-archiver/data/` 或 `bin/data/`）；
   > 拷 `db` + `db-wal` + `db-shm` 三个文件。

2. **发版**：`git push origin master` → Actions 构建镜像 → 服务器拉取 → `docker compose up -d`
   → workflow 现在还会 `wget` 新的 `nginx-default.conf` 并 `docker compose restart nginx`。
   容器启动时 `schema.sql` 自动建表；`daily_sync` 用户需要 CREATE 权限（本机已验证有）。

3. **迁移旧存档**：浏览器打开 `http://120.26.58.22:8080/omofun/`，用 ADMIN 登录 →
   设置页 →「迁移旧 SQLite 存档」。成功提示 `条目 55，流水 242，运行记录 8`（数字以旧库为准）。

4. **验证**：
   - `/omofun/` 页面登录后总览有数据；
   - 点「立即存档」→ 任务跑完，`最近运行` 新增一行；
   - 点「打开报告」→ 全屏 iframe 报告有卡片；
   - `curl /healthz` 200，门户 `/` 与 `/app/` 不受影响；
   - 浏览器 `localStorage["daily-sync-auth"]` 与 `/app` 共用。

5. **清理**：旧 systemd 服务已 stop + disable；`/opt/omofun-archiver` 目录保留作回滚备份，
   确认稳定后再手动删。

### 10.1 实际部署记录（2026-11-08）

- push master（commit `17432ab`，含之前待推的 `713fb8b`）→ Actions run `42477328161`，
  30m07s 成功（构建 + Release + 服务器拉取 + load/up + smoke）。
- **镜像拉取救急**：服务器 mihomo 7890 不可用，GitHub Release 直连只有 ~15KB/s；
  改用 `gh-proxy.com` 下载 158MB 镜像（实测 ~450KB/s）。下载到独立 `.proxy` 文件，
  `gzip -t` 校验通过后替换为 `daily-sync.tar.gz` 并 `touch .pull-done`，workflow 的轮询
  看到标记后继续 `docker load` + `compose up`。不要与 workflow 的 `.new` 文件并发写。
- 旧 `omofun-archiver` 已 stop + disable；`auth-tokens.json`、`server.crt`、
  `omofun_history.db`（含 `-wal`/`-shm`）已拷到 `/opt/daily-sync/data/omofun/`。
- 生产迁移接口返回 `0/0/0`：本地 e2e 已把同一份旧存档迁进 MySQL，服务端 SQLite 没有
  额外条目；progress_log/poll_runs 按设计在非空表时跳过。
- 生产 poll：57 条 / 4 页，0 新增 / 57 更新；`/healthz`、`/`、`/app/`、
  `/api/v1/downloads`、`/omofun/` 均 200；未登录 `/api/v1/omofun/overview` 401。
- 线上页面：UID 2436842、57 条、14 张最近卡片、8 行运行记录，登录遮罩正常隐藏。

---

## 11. 本地验证记录（2026-11-08）

| 项目 | 结果 |
|---|---|
| `mvn -DskipTests compile` / `package` | 通过，jar 52MB |
| 真实 gRPC 探测 | 成功拉 5 条，UID 2436842 |
| schema.sql 启动建表 | 4 张表 + settings 默认行 |
| 未登录访问 `/api/v1/omofun/**` | 401 |
| 旧 SQLite 迁移 | 55 entries / 242 progress_log / 8 runs |
| 真实 poll | 57 条 / 4 页；新增 2，更新 55，重置 0 |
| 真实 keepalive（maxWrites=1） | 续命 1，冲突 0，失败 0 |
| 报告 iframe | 57 张卡，统计/封面/进度条正常 |
| 静态 `/omofun/` | 302 → `/omofun/` 200 |
| 前端浏览器测试 | 总览 57、浏览 57 卡、设置页调度正常、报告遮罩开/关正常 |

---

## 12. 已知坑（本次新增）

1. **`[hidden]` 被 `.login-overlay { display:flex }` 压过**：作者样式优先于 UA 样式，
   登录遮罩刷新后一直盖住页面。已在 `frontend/omofun/style.css` 与
   `omofun-archiver/ui/style.css` 加 `[hidden] { display: none !important; }`。
2. **`window.open` 在 await 之后被弹窗拦截**，内置浏览器甚至同步调用也返回 null。
   报告改为「全屏遮罩 + iframe.srcdoc」，不再依赖弹窗。
3. **旧时间格式溢出 VARCHAR(32)**：chrono 的 `to_rfc3339()` 带纳秒和 `+00:00`；
   迁移时必须归一化。`title`/`episode_title` 放宽为 TEXT 以容纳旧标题。
4. **`spring.sql.init.mode=always`**：schema.sql 必须保持幂等；不要在里面放
   `ALTER TABLE`（每次启动都会跑）。
5. **token 失效**：服务器用的是 `auth-tokens.json` 的拷贝；若 OmoFun 端把 token 作废，
   需从桌面客户端重新拷一份。页面会提示 UNAUTHENTICATED。
6. **nginx 配置是 bind mount**：`docker compose up -d` 不会因文件内容变化重建容器，
   workflow 已加 `docker compose restart nginx`。
7. **旧服务下线顺序**：先停 `omofun-archiver` 再拷 SQLite，避免 WAL 尾部丢失。
8. **ADMIN 限制**：`AuthInterceptor` 对 `/api/v1/omofun/` 前缀要求 ADMIN；
   普通用户会收到 403「需要管理员权限」。
9. **Release 直连慢 / mihomo 代理挂**：服务器直连 GitHub Release 实测 ~15KB/s；
   `gh-proxy.com` 实测 ~450KB/s 可救急。应急下载必须落到独立文件，`gzip -t` 校验后
   再替换 `daily-sync.tar.gz` 并 `touch .pull-done`，让 workflow 继续 load/up。

---

## 13. 回滚

- 数据：MySQL `omofun_*` 表与旧 SQLite 都在，互不影响；旧服务目录保留。
- 服务：`systemctl enable --now omofun-archiver`，把 `nginx-default.conf` 恢复 M10 版本
  （`/omofun/ → host.docker.internal:8082`），`docker compose restart nginx`。
- 新模块：`OMOFUN_ENABLED=false` 可让接口统一 503（调度器也停止），不影响 daily-sync
  其余功能；或直接回退到上一个 daily-sync 镜像。
