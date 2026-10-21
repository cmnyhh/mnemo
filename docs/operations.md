# 部署与运维

## 需要什么

- 2 核 / 4G 内存 / 20G 空闲盘。实测三个容器空载合计约 360MB，3～5 万条记忆在舒适区内。
- Docker + Compose v2（`docker compose version` 能出版本）。
- 想拿到语义召回，再准备一个 OpenAI 兼容的 embedding 端点；精排与抽取可选。

不要在 2 核机器上现场编 Java：编一次要十几分钟且吃满内存。用镜像，或者在自己的机器 / CI 上编完再推过去。

## 起服务

```bash
./scripts/setup-env.sh
# 等价于：cp .env.example .env && chmod 600 .env，再把三个凭据换成随机值
# （POSTGRES_PASSWORD / QDRANT_API_KEY / MEMORY_ADMIN_PASSWORD）。
# 脚本结束时会把登录账号与口令打在屏幕上。
```

用预构建镜像：

```bash
docker compose up -d
```

镜像由 GitHub Actions 构建，同时发布到 Docker Hub 和 GHCR。所在网络拉不到 Docker Hub 时，改两个变量即可切到 GHCR：

```bash
MNEMO_IMAGE=ghcr.io/cmnyhh/mnemo:1.1.0 \
MNEMO_DB_IMAGE=ghcr.io/cmnyhh/mnemo-db:18 \
docker compose up -d
```

从源码构建（改过代码时）：

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
```

`db` 首次启动会编译 `pg_textsearch`，约 2 分钟。只想先起存储、确认扩展就绪：

```bash
docker compose up -d db qdrant
docker compose logs -f db
```

## 账号与口令

一个部署有四个入口，各有一套凭据，互不影响：

| 入口 | 默认账号 | 口令来源 | 能改吗 |
| --- | --- | --- | --- |
| 控制台 | `admin`（`MEMORY_ADMIN_USERNAME`） | `.env` 的 `MEMORY_ADMIN_PASSWORD`，首次启动写库 | 控制台「系统」页 |
| PostgreSQL | `memory`，库名 `memory` | `.env` 的 `POSTGRES_PASSWORD` | 改 `.env` 后重建容器 |
| Qdrant | 无账号 | `.env` 的 `QDRANT_API_KEY`，放在 `api-key` 请求头 | 改 `.env` 后重建容器 |
| 记忆 API | 无账号 | 控制台「令牌」页签发的 `mn_…` | 随签随撤 |

`MEMORY_ADMIN_PASSWORD` 留空 = 控制台关闭（页面能打开，但登录不了）；记忆 API 照常工作。控制台口令只在**首次启动**时写库，之后改 `.env` 不会再覆盖——忘了口令就在「系统」页用原口令改，或者清掉 `admin_credential` 表那一行重启。

`db` 与 `qdrant` 只绑 `127.0.0.1`，所以这两个口令在本机之外用不到；要远程连库请自己加 SSH 隧道，别把它们开到公网。

## 起来了没有

```bash
# 扩展装了没（应该只有 pg_textsearch）
docker compose exec db psql -U memory -d memory -c '\dx'

# 预加载生效没（空的话 BM25 索引建不出来）
docker compose exec db psql -U memory -d memory -c 'show shared_preload_libraries'
```

BM25 端到端（建索引、查一次）：

```bash
docker compose exec db psql -U memory -d memory <<'SQL'
create table if not exists bm25_probe (id bigserial primary key, c text);
-- 中文已按应用层的分词结果写成空格分隔
insert into bm25_probe (c) values ('部署 在 10.0.0.12 的知识库 服务 用 8080 端口');
create index if not exists bm25_probe_idx on bm25_probe using bm25(c) with (text_config='simple');
set enable_seqscan = off;
explain select * from bm25_probe order by c <@> '部署 端口' limit 5;
SQL
```

计划里出现 `Index Scan using bm25_probe_idx` 就说明索引真的能被用上。

语法以实测为准：`pg_textsearch 1.6.0-dev` 的排序用 `<@>`。写成 `order by c '部署 端口'`（老文档里的形式）会被 PG 解析成 `'部署 端口'::c`，报 `type "c" does not exist`——看着像扩展没装好，其实是语法换代了。

Qdrant 通不通：

```bash
curl -s -H "api-key: $(grep QDRANT_API_KEY .env | cut -d= -f2)" \
  http://127.0.0.1:6333/collections | head -c 200
```

## 配置从哪来

**环境变量是基线，控制台只覆盖它。** 控制台保存的只写进 `app_config` 表（只存改过的项），界面上点「恢复」就退回 `.env` 的值。这样「容器里写了什么」和「界面上看到什么」始终能对上。

保存后热生效，不需要重启容器——换模型本来就要紧接着重建索引，把「保存」和「生效」拆成两步只会让排障更难。两个标签页同时保存时，后一个会拿到 409（revision 乐观锁），不会静默覆盖。

完整键名与含义见 [`.env.example`](../.env.example)，控制台「配置」页会把每个字段的基线与取值范围一起显示出来。

## 控制台

地址就是服务根路径 `http://<主机>:8090/`。它只做四件事：看记忆、试召回、改配置、签发令牌。

鉴权是一个账号（用户名 + 口令），与 API 令牌完全分开：

```bash
MEMORY_ADMIN_USERNAME=admin          # 首次启动写入库，之后以库为准
MEMORY_ADMIN_PASSWORD=<openssl rand -base64 18>
MEMORY_ADMIN_SESSION_HOURS=12
MEMORY_ADMIN_COOKIE_SECURE=auto     # auto / true / false
```

- 首次启动把账号与口令哈希（PBKDF2-HMAC-SHA256，21 万次迭代）写进 `admin_credential`，之后**以库里为准**；改账号或口令在「系统」页做，改完所有会话立即作废。
- 账号**大小写不敏感**；口令错和账号错返回同一句「账号或口令不正确」，且两条路径都先算一次哈希再比账号，耗时一致——从响应上分不出账号是否存在。
- 会话是服务端内存里的随机令牌，cookie 为 `HttpOnly + SameSite=Strict`；写请求还要带会话绑定的 `X-CSRF-Token`。**重启服务 = 重新登录**，这是有意的：没有一张表可以被人 dump 后直接冒用。
- 登录失败按来源 IP 限流（5 次 / 5 分钟），失败响应固定 120ms，避免把登录页变成离线字典攻击的加速器。
- 口令留空则控制台关闭；**记忆 API 不受影响**。库里有凭证之后，环境变量里的账号口令不再生效——否则「改了 .env 没反应」会变成新的困惑源。
- 嵌入模型的 API Key 存在 `app_config`（明文，数据库只监听 127.0.0.1）。这是单机自托管的取舍：换来「换模型不用改 .env 再重启」；如果这把 Key 更敏感，仍应放 `.env` 并在界面上留空。

**重建索引**（配置页 / 系统页）会删掉 Qdrant 集合、按当前维度重建，再把 PG 里所有 `active` 记录重新嵌入一遍，带实时进度。向量是派生数据，PG 才是账本；重建期间 BM25 召回照常工作，只是 dense 这一路暂时为空。

> 安全边界：控制台是「有口令的单用户后台」，不是多租户 RBAC。对外暴露请走 HTTPS 反代（`MEMORY_ADMIN_COOKIE_SECURE=true`），不要把 8090 直接开到公网。

## 令牌

`/v1/memory/*` 的 Bearer 令牌只有一个来源：`api_token` 表，由控制台「令牌」页签发。

- **只存哈希**：明文是 `mn_` + 43 位 base64url（32 字节随机数），库里只有 sha256 十六进制串和一个 11 位前缀。创建之后任何接口都不回显明文——想再看一眼只能重新签发。
- **可设过期**（1–3650 天，留空永不过期），列表里能看到「最后使用时间」（同一把令牌每分钟最多刷一次，它是观测字段，不值得每个请求一次 update）。
- **撤销立即生效**，不需要重启。删除只允许针对「已撤销或已过期」的行，活跃令牌必须先撤销。
- **租户随令牌走**：签发时绑定当前租户，调用方不需要也不能自己指定。
- 校验顺序是先比环境变量令牌（等值比较）再查表（一次索引查询）；401 的文案对两种情况相同，区分「不存在」和「已过期」等于告诉攻击者哪段前缀猜对了。

表里的行全部撤销或过期后，`/v1/*` 一律 401；但控制台走口令 + 会话，照样进得去，在那里签一把新的就恢复了，不需要登录服务器改配置。

```bash
curl -H "Authorization: Bearer mn_xxx" https://<主机>/v1/memory/health
```

## 存储与清理

控制台「系统」页会给出四项账目：记忆条目（生效 / 保留）、PostgreSQL 占用、向量副本（条数与估算占用）、附件占用，以及磁盘可用量。

- **保留 = 被取代 + 已遗忘**。它们留在 `memory_item` 里，可以在「记忆库」按状态筛出来；检索只查 `active`，所以它们本来就不会被注入。
- **清理归档向量**只删这些条目的向量副本，不动条目本身。删完 `points ≈ active`，磁盘回收、`I9` 也不再报差额。这一步可逆性由 PG 保证：真要重新用，重建索引会把它们按 `active` 再嵌一遍（届时它们也重新参与召回）。
- 向量占用是 `条数 × 维度 × 4 字节` 的估算，不含 payload 与 HNSW 图；体感上真实占用是它的 1.3–1.6 倍。

容量参考（4096 维）：1 万条约 160MB 向量，3–5 万条在 2 核 4G 机器上仍然舒适；10 万条光向量 1.6GB，会开始挤页缓存。

## 备份

```bash
./scripts/backup.sh                    # 手动一次
crontab -e                             # 每天 03:20
20 3 * * * /srv/mnemo/scripts/backup.sh >> /var/log/mnemo-backup.log 2>&1
```

脚本会 `pg_restore --list` 回读一遍，只看文件大小会漏掉截断的 dump。Qdrant 不在备份范围内：它是派生数据，从 PG 回填。

## 对外访问

`db` 与 `qdrant` 只绑 `127.0.0.1`，`service` 的 8090 绑 `0.0.0.0`——因为反代通常跑在另一个容器/另一台机器上，绑回环它就连不上。数据库与向量库没有鉴权，不能这样放开。

反代（nginx-proxy-manager 或任意 nginx）里配 **Scheme = `http`、Port = `8090`**：

```nginx
location / {
    proxy_pass http://<宿主机地址>:8090;
    proxy_set_header Host              $host;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;   # 缺这条 cookie 不会带 Secure
}
```

四个容易踩的点：

- **上游是 http，不是 https。** 服务本身不做 TLS。写成 `https` 时 nginx 会去和一个明文端口握手，结果是一律 `502 Bad Gateway`，日志里是 `SSL_do_handshake() failed`。
- **`Host` 必须和反代的 `server_name` 对上。** 对不上时请求落到默认站点，看到的通常是一个 nginx 默认页的 `404`，容易误判成服务没起来。
- **`X-Forwarded-Proto` 决定 cookie 带不带 `Secure`**，也决定登录限流按哪个 IP 计数（取 `X-Forwarded-For` 的第一段）。
- **TLS 在反代终止**，证书要覆盖实际访问的域名（通配符 `*.example.com` 可以，只签 `example.com` 的证书不行）。

排查顺序：先在服务器本机 `curl -H "Host: <域名>" http://127.0.0.1:8090/` 打服务，再打反代，最后才从公网打。第二步和第三步不一致，问题就在端口转发，不在这套服务里。

## 内存账（实测，2 核 / 3.9G 机器）

| 服务 | mem_limit | 空载实测 |
|---|---|---|
| db | 768m | **55 MB** |
| qdrant | 320m | **65 MB** |
| service | 1280m | **236 MB** |
| 合计 | 2368m | **356 MB** |

空载这么低是因为 `shared_buffers` 是预留不是占用——真正吃内存的是数据量和页缓存，两者都随记忆条数长。按 4096 维算，3–5 万条是舒适区（10 万条光向量就 1.6GB，超出可用页缓存）。

**不要把三个 limit 加起来等于可用内存。** 那样页缓存没地方待，向量查询每次都走磁盘，P99 会从几毫秒掉到几十毫秒——而且看起来像「Qdrant 性能不行」。

## 观测

**指标端点**：`GET /internal/metrics`（Prometheus 文本格式）。它和控制台的记忆 API 在同一个端口上，所以**必须带 Bearer 令牌**——`ApiTokenFilter` 对它不豁免。

```bash
curl -s -H "Authorization: Bearer $MNEMO_TOKEN" http://127.0.0.1:8090/internal/metrics | head
```

Prometheus 侧：

```yaml
scrape_configs:
  - job_name: mnemo
    metrics_path: /internal/metrics
    authorization:
      credentials_file: /etc/prometheus/mnemo.token
    static_configs:
      - targets: ['127.0.0.1:8090']
```

只有 `prometheus` 与 `health` 两个端点被暴露（`management.endpoints.web.exposure.include`）。`env`、`beans`、`heapdump` 这类端点对公网等于内部结构图，没有任何运维场景需要它们。

| 指标 | 含义 | 怎么读 |
| --- | --- | --- |
| `mnemo_search` | 端到端召回耗时，标签 `mode` / `outcome` | 用户感受的那段时间 |
| `mnemo_search_stage` | 分阶段耗时，标签 `stage` = `embed` / `bm25` / `dense` / `rerank` | 慢在哪一段 |
| `mnemo_ingest` | 端到端写入耗时，标签 `extraction` | 写入慢通常是抽取模型 |
| `mnemo_search_outcome` | `hit` / `abstain` / `empty` / `error` 计数 | 弃权率持续升高比错误率更值得查 |
| `mnemo_rerank` | `applied` / `skipped` / `failed` | 一直 `skipped` 说明阈值偏宽 |
| `mnemo_degrade` | `vector` / `rerank` / `extract` 降级次数 | 非零就是正在带病跑 |
| `mnemo_ingest_review` | `pass` / `downgrade` | 写入审查命中数 |

**从分阶段耗时定位问题**：`embed` 高通常是嵌入服务往返（公网 150~560ms 属正常）；`bm25` 高看 PG 的 `shared_buffers` 与索引预加载；`dense` 高看 Qdrant 是否被挤到走磁盘；`rerank` 高看精排服务。四段相加明显小于总值时，差值花在 PG 复核与注入块拼装上。

**控制台「观测」页**给的是同一份数字（同一 `MeterRegistry`），不需要额外部署 Prometheus 就能看 p50/p95/最大与分阶段排序。

**审计日志**落在 `audit_log` 表（无 RLS，只有控制台读写）：

```sql
select occurred_at, actor, action, target, outcome, ip
from audit_log order by occurred_at desc limit 20;
```

记录的动作：登录/退出（含失败与限流）、改账号口令、改配置（只记改了哪几个键）、重建索引、清理向量、遗忘、钉住/取消、分层治理、令牌签发/撤销/删除。保留 180 天，按概率顺手清理，不另起定时任务。`detail` 里凡是口令、令牌、密钥字段一律打码；记忆正文从不进入审计表。

## 巡检

```bash
bash scripts/invariants.sh
```

按不变量逐条判定（向量点与登记表是否一致、RLS 是否生效、库与令牌是否只属配置租户等）。能靠数据判的靠数据判，判不了的显式 `SKIP` 并写清原因。有违规时退出码非 0，可以挂进定时任务。

I9 只把「向量点找不到对应记录」当违规：被取代或已遗忘的记忆，其向量副本是**故意留着**的（用户可能要翻旧账），单独计数、不判失败。想回收这部分磁盘，去控制台「系统 · 存储」点「清理归档向量」。

## 已知边界

- **PG 18 的镜像挂载点是 `/var/lib/postgresql`，不是 `.../data`。** 从 18 起官方镜像改成版本化子目录布局，挂到 `.../data` 会直接拒绝启动并让人以为卷里是旧数据（docker-library/postgres#1259）。
- `db/init/` 只在 pgdata 为空时执行。之后加脚本不会自动跑，扩展要手工 `CREATE EXTENSION`。
- `pg_textsearch` 的 `main` 分支是滚动的。生产构建请把 `PG_TEXTSEARCH_REF` 钉到 release tag，否则某天重新构建会拉到不同的代码。
- 构建 `db` 镜像时只能用 codeload 的 tarball，不能 `git clone`（`github.com` 在部分网络环境不通，`codeload.github.com` 通）。
- **`embedding.provider=hash` 时向量检索不可用**，召回只剩 BM25。health 会照实回报，启动日志也会 warn。别把这个状态当成品。
- 单租户：`tenantId` 来自 `MEMORY_TENANT` 配置，一个部署一个值。多租户需要另行改造（见 [设计 · 租户与数据安全](design.md#租户与数据安全)）。
- 记录层召回没有按会话过滤：跨会话的记忆会被召回，这是**有意的**——记忆的意义就是跨会话；同一个人的两个互不相关项目之间会互相干扰，`projectId` 只提供排序偏好。
