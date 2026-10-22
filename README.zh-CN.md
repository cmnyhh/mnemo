<p align="center">
  <img src="docs/screenshots/overview.png" alt="mnemo 控制台" width="820">
</p>

<h1 align="center">mnemo</h1>

<p align="center">自托管的 AI 长期记忆服务。</p>

<p align="center">
  <a href="https://hub.docker.com/r/yejiu233/mnemo"><img src="https://img.shields.io/docker/pulls/yejiu233/mnemo?label=docker%20pulls" alt="Docker pulls"></a>
  <a href="https://www.npmjs.com/package/@cmnyhh/dsh-mnemo"><img src="https://img.shields.io/npm/v/@cmnyhh/dsh-mnemo?label=npm" alt="npm"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
</p>

<p align="center">
  <a href="README.md">English</a> · 简体中文
</p>

---

对话助手的记忆通常只有两种做法：会话结束即清空，或者把整段历史塞进上下文，直到窗口和成本成为限制。mnemo 采用第三种：把对话中确定下来的内容持久保存，每次只返回与当前请求相关的条目。

服务运行在你自己的机器上。数据保存在自建的 PostgreSQL 与 Qdrant 中，模型端点可以自行选择，也可以不配。

## 快速开始

```bash
git clone https://github.com/cmnyhh/mnemo.git && cd mnemo
./scripts/setup-env.sh    # 生成凭据，并打印一次
docker compose up -d
```

脚本会输出以下信息：

| 入口 | 地址 | 账号 | 凭据 |
| --- | --- | --- | --- |
| 控制台 | <http://localhost:8090> | `admin` | 脚本生成，仅显示一次 |
| PostgreSQL | `127.0.0.1:5432` | `memory`（库名 `memory`） | `POSTGRES_PASSWORD` |
| Qdrant | `127.0.0.1:6333` | 无 | 请求头 `api-key: $QDRANT_API_KEY` |
| 记忆 API | `/v1/memory/*` | 无 | 控制台签发的 `mn_…` |

凭据保存在 `.env`。首次启动后修改 `.env` 不会重置控制台口令：它在首次启动时写入数据库，之后在控制台修改。若需手工配置，参见 `.env.example` 中的注释。

镜像由 GitHub Actions 构建，发布到 [Docker Hub](https://hub.docker.com/r/yejiu233/mnemo)，`docker compose up` 默认拉的就是它；同样的标签也镜像到 GHCR，拉不到 Hub 的网络把 `MNEMO_IMAGE` / `MNEMO_DB_IMAGE` 设成 `ghcr.io/cmnyhh/mnemo:1.4.9` 与 `ghcr.io/cmnyhh/mnemo-db:18` 即可。需要从源码构建时，叠加构建文件即可，首次约十分钟：

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
```

打开 <http://localhost:8090> 登录，在「令牌」页签发令牌，再到「召回」页试一次查询。

通过接口读写：

```bash
TOKEN=mn_你的令牌

curl -X POST http://localhost:8090/v1/memory/ingest \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"kind":"fact","narrative":"服务监听 8090，前置 HTTPS 反向代理","importance":0.8}'

curl -X POST http://localhost:8090/v1/memory/search \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"query":"服务用哪个端口"}'
```

第二次调用返回刚写入的条目，包含分数、命中的检索通道，以及可直接放入提示词的 `injection` 字段。

## 用 Docker Compose 部署

仓库里的 compose 文件包含三个服务：`db`（PostgreSQL 18 + BM25）、`qdrant`、`service`。在装了 Docker 与 Compose v2 的服务器上，两个文件即可起一套，不需要克隆仓库。

`compose.yaml`：

```yaml
name: memory

services:
  db:
    image: yejiu233/mnemo-db:18
    restart: unless-stopped
    shm_size: 256mb
    mem_limit: 768m
    environment:
      POSTGRES_DB: memory
      POSTGRES_USER: memory
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?请在 .env 里设置 POSTGRES_PASSWORD}
      TZ: Asia/Shanghai
    command:
      - postgres
      - -c
      - shared_buffers=384MB
      - -c
      - maintenance_work_mem=128MB
      - -c
      - effective_cache_size=1536MB
      - -c
      - shared_preload_libraries=pg_textsearch
    volumes:
      - pgdata:/var/lib/postgresql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U memory -d memory"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 20s
    ports:
      - "127.0.0.1:5432:5432"

  qdrant:
    image: qdrant/qdrant:v1.12.4
    restart: unless-stopped
    mem_limit: 320m
    environment:
      QDRANT__SERVICE__API_KEY: ${QDRANT_API_KEY:?请在 .env 里设置 QDRANT_API_KEY}
      QDRANT__STORAGE__ON_DISK_PAYLOAD: "true"
      QDRANT__TELEMETRY_DISABLED: "true"
    volumes:
      - qdrant:/qdrant/storage
    ports:
      - "127.0.0.1:6333:6333"

  service:
    image: yejiu233/mnemo:1.4.9
    restart: unless-stopped
    mem_limit: 1280m
    environment:
      MEMORY_DB_URL: jdbc:postgresql://db:5432/memory
      MEMORY_DB_USER: memory
      MEMORY_DB_PASSWORD: ${POSTGRES_PASSWORD}
      MEMORY_QDRANT_URL: http://qdrant:6333
      MEMORY_QDRANT_API_KEY: ${QDRANT_API_KEY}
      MEMORY_TENANT: ${MEMORY_TENANT:-default}
      MEMORY_ADMIN_USERNAME: ${MEMORY_ADMIN_USERNAME:-admin}
      MEMORY_ADMIN_PASSWORD: ${MEMORY_ADMIN_PASSWORD:-}
      MEMORY_ADMIN_COOKIE_SECURE: ${MEMORY_ADMIN_COOKIE_SECURE:-auto}
      MEMORY_EMBEDDING_PROVIDER: ${EMBEDDING_PROVIDER:-hash}
      MEMORY_EMBEDDING_BASE_URL: ${EMBEDDING_BASE_URL:-}
      MEMORY_EMBEDDING_API_KEY: ${EMBEDDING_API_KEY:-}
      MEMORY_EMBEDDING_MODEL: ${EMBEDDING_MODEL:-qwen3-embedding-8b}
      MEMORY_EMBEDDING_DIMS: ${EMBEDDING_DIMS:-4096}
      MEMORY_ASSETS_DIR: /data/assets
      TZ: Asia/Shanghai
    depends_on:
      db:
        condition: service_healthy
      qdrant:
        condition: service_started
    volumes:
      - assets:/data/assets
    ports:
      - "${MNEMO_PORT:-8090}:8080"
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1:8080/v1/memory/health >/dev/null || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 3
      start_period: 40s

volumes:
  pgdata:
  qdrant:
  assets:
```

`.env`：只有两个凭据是必填，其余变量都有默认值。下面这段让 shell 直接把随机值写进去：

```bash
cat > .env <<EOF
POSTGRES_PASSWORD=$(openssl rand -base64 24 | tr -d '\n')
QDRANT_API_KEY=$(openssl rand -base64 24 | tr -d '\n')
MEMORY_ADMIN_PASSWORD=$(openssl rand -base64 18 | tr -d '\n')
EOF
chmod 600 .env
docker compose up -d
docker compose ps        # 等到 service 显示 healthy
```

`MEMORY_ADMIN_PASSWORD` 在首次启动时读取并写进数据库；之后再改 `.env` 不会覆盖，要改请走控制台。

对外之前值得知道的几点：

- **只有 `service` 对外开端口。** PostgreSQL 与 Qdrant 只绑 `127.0.0.1`；`service` 监听 `0.0.0.0:8090`，是为了让另一个容器里的反代能连上。没有 HTTPS 反代时，请把它改成 `127.0.0.1:8090:8080`。
- **卷名带着数据。** 三个卷以项目名（`name: memory`）为前缀，改名等于换一套栈，挂的是空卷——看起来就像数据丢了。
- **升级**是 `docker compose pull && docker compose up -d`。schema 幂等、启动时应用，不需要单独的迁移步骤。
- **备份 PostgreSQL 即可**，向量与全文索引都是派生的、能从它重建；用了附件的话再带上 `assets` 卷：`docker compose exec db pg_dump -U memory memory | gzip > memory-$(date +%F).sql.gz`。
- **不带仓库里的 `db/init` 也能跑**：需要的扩展由服务在首次启动时创建。
- **语义召回要配 embedding 端点**：上面默认 `hash`，那是本地确定性向量，只够把链路跑通；要真召回请把 `EMBEDDING_PROVIDER` 改成 `http` 并填端点（也可以之后在控制台「配置」页配）。

完整变量表、反代示例与资源配置见[部署与运维](docs/operations.md)。

## 接入 Agent

插件已发布到 npm：[`@cmnyhh/dsh-mnemo`](https://www.npmjs.com/package/@cmnyhh/dsh-mnemo)。

```bash
npm install @cmnyhh/dsh-mnemo
```

npm 地址：<https://www.npmjs.com/package/@cmnyhh/dsh-mnemo> · 镜像地址：<https://hub.docker.com/r/yejiu233/mnemo>

没有插件宿主时，直接调用接口：

```bash
curl -s -X POST https://<你的域名>/v1/memory/search \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"query":"上次确定的部署方案"}' | jq -r .injection
```

插件（[`plugin/`](plugin/README.md)）在每轮结束后写入、下一轮开始前注入，并把召回注册为模型可主动调用的工具。

要让它学的是**做法**而不只是事实，把跑完的一次任务发到 `/v1/memory/trajectories/record`：目标、步骤、结果。同类任务成功够多次之后，`/v1/memory/skills/{name}` 会返回一份可直接使用的 `SKILL.md`。

## 能力

**保存条目，不保存整段对话。** 写入时把对话归并为事实与偏好，每条带独立的主语、有效期和来源。条目可被取代、遗忘或钉住，且不影响其余数据。

**召回可以弃权。** 每次请求都会打分，最佳候选未达阈值时不注入任何内容。缺失一条记忆的代价低于注入一条错误的记忆。

**分层可见性。** 钉住的条目每轮注入，工作集随会话结束失效，长期层默认参与检索，归档层需显式指定层级才会出现。

**记忆是数据，不是指令。** 注入内容整体包裹并标注为历史记录。来自外部来源的内容会被降级并标记，形似命令的文本不会因此获得执行权。

**做法会变成技能。** 除了「发生过什么」，也记「这事是怎么做成的」：每一步保留动作、观察、反思与价值。轨迹关闭时终局奖励沿步骤回传，写得具体的反思权重更高。同一类任务反复成功会沉淀为策略，策略的证据足够则结晶成可直接取用执行的 `SKILL.md`。只有成功样本参与结晶，观察里出现指令式内容会整份隔离。

**观测，而非猜测。** 召回耗时按阶段拆开——嵌入、关键词、向量、精排——并从 `/internal/metrics` 导出给 Prometheus；控制台上的每个动作都写入审计日志，含操作人、对象与来源地址。

**索引是派生的。** 记录保存在 PostgreSQL，向量与全文索引随时可从它重建，更换模型或维度后同理。

**历史保留。** 已被取代或已遗忘的条目在控制台中按状态可查。其向量副本可在「系统 · 存储」回收，条目本身不受影响。

**一个部署，一个租户。** 不模拟多租户。令牌可撤销、可设有效期，单条记忆可遗忘，控制台凭据与 API 令牌相互独立。

## 适用范围

一个实例服务一台主机上的一个团队。它不是分布式系统，没有内置复制，也不按最终用户切分数据。需要按用户隔离的场景，应为每个租户部署一个实例。

## 控制台

不依赖命令行即可完成整套操作：浏览条目、执行召回测试、修改模型配置、签发令牌、重建索引、查看存储占用。

<table>
  <tr>
    <td><img src="docs/screenshots/records.png" alt="记忆库"></td>
    <td><img src="docs/screenshots/recall.png" alt="召回测试"></td>
  </tr>
  <tr>
    <td align="center">记忆库：每条记录可追溯到来源轮次与层级</td>
    <td align="center">召回测试：分数、通道与注入块</td>
  </tr>
</table>

## 资源要求

两核 4 GB 即可。三个容器空载合计约 360 MB，3 万至 5 万条记录在舒适区间内。数据全部落在 Docker 卷中，只需备份 PostgreSQL。「系统 · 存储」页显示数据库、向量副本、附件占用与剩余磁盘。

召回质量依赖两个可选的 OpenAI 兼容端点：向量与精排。抽取模型同样可选，配置后按事实写入，不配置则按段落切分。三者都不是必需项——服务可以启动、可以写入，检索退化为关键词匹配。

## 文档

| | |
| --- | --- |
| [设计](docs/design.md) | 检索、抽取、分层、信任边界、数据模型 |
| [部署与运维](docs/operations.md) | 配置、控制台、令牌、备份、反向代理 |
| [接口](docs/api.md) | `/v1/memory/*` 全部端点 |
| [开发](docs/development.md) | 本地运行、测试、构建、发布 |
| [插件](plugin/README.md) | 安装、配置项、行为约定 |

## 许可

MIT
