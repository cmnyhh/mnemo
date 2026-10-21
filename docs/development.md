# 开发

## 仓库长什么样

```
service/            Spring Boot 3 / JDK 21；HTTP 面、检索、写入、治理
console/            Vue 3 + Naive UI + Vite；构建产物由 Dockerfile 打进 jar 的 static/
plugin/             dsh 插件（npm 包 mnemo-memory-core）
db/                 postgres:18 + pg_textsearch 的镜像
scripts/            备份、建集合、冒烟、不变量巡检
tools/             召回评测脚本与样例 query
docs/              设计、运维、接口文档
docker-compose.yml 用预构建镜像起服务
docker-compose.build.yml  从源码构建的叠加层
```

两条工具链互不越界：**Maven 只管 Java，npm 只管 `console/` 与 `plugin/`。** 仓库里不放构建产物（`console/dist`、`plugin/index.js` 都不进 git），避免「改了源码忘了重建」。

## 服务端

```bash
mvn -f service/pom.xml test      # 全部单测，纯 JUnit，不需要 PG / Qdrant
mvn -f service/pom.xml package   # 出可执行 jar
```

单测覆盖检索打分、分层策略、配置解析、口令哈希、写入审查、向量对账等纯逻辑。需要数据库的部分（仓储 SQL、RLS）用集成测试之外的脚本验证，见 `scripts/`。

编译交给容器或 CI，不必在目标机上装 JDK。

## 控制台

```bash
cd console
npm ci
npm run typecheck     # vue-tsc --noEmit，vite build 不做类型检查
npm run build
CONSOLE_PROXY=http://127.0.0.1:8090 npm run dev   # 对着远端服务开发，目标可以是 SSH 隧道口
```

```
src/
  router.ts         hash 路由（6 个页面，按需加载）
  components/       AppRoot（会话引导）· AppShell（侧栏 + 顶栏）· ConfigInput（按元数据渲染控件）
  views/            Login / Overview / Records / Recall / Config / Tokens / System
  lib/              api（CSRF 与 401 归一）· store · reindex（跨页轮询）· feedback · format · theme
```

几个刻意的选择：

- **hash 路由**。静态资源由 Spring Boot 直接吐，用 history 模式就得在服务端加一条 404 回退；少一条规则，就少一个「本地好、线上 404」的来源。
- **配置表单不认具体键名**。控件类型、选项、范围、默认值全部来自 `/admin/api/config` 的 `fields`；服务端加一个配置项，界面不用改一行。
- **静态资源要放行**。`ApiTokenFilter` 白名单包含 `/assets/`：带 hash 的产物文件名无法逐个列举，漏了这条的表现是 HTML 能打开、JS 全 401，页面白屏而日志干净。
- **前端不引 CDN**。内网部署不能依赖外网，字体用系统字体，图标用内置 SVG。CI 会扫产物里的外链，扫到就红。

体积参考：`naive` chunk 707 KB / gzip 203 KB，各页 4~9 KB。单机内网后台，一次加载后走缓存，这个量级可以接受。

## 插件

```bash
cd plugin
npm install
npm run build     # esbuild 打包 src/ → index.js
npm run smoke     # 假宿主冒烟：挂载、写入、下一轮召回、工具调用、失败语义
```

`index.js` 不进仓库：`prepublishOnly` 在发布前重新打包。

宿主的 `@deepseek-ai/cordis` 与 `dsh-*` 保持 external（打进来会出现两份上下文，插件挂不上宿主）；`@deepseek-ai/schemastery` 是插件自己的依赖，打进产物。

`tests/` 里的 vitest 套件依赖宿主的测试夹具（`@deepseek-ai/dsh-agent-loop-testkit` 等），要在 dsh 源码树里跑；在独立仓库里用 `smoke.mjs` 验证打包产物。

## 集成测试

服务起来之后，端到端走一遍写入 → 召回 → 覆盖 → 弃权 → 重启后仍在：

```bash
MEMORY_TOKEN=mn_xxx ./scripts/smoke.sh
```

巡检不变量（向量点与登记表、RLS、租户归属）：

```bash
bash scripts/invariants.sh
```

## CI

推到 `main` 或开 PR，`ci.yml` 跑四个 job：

- **test**：Java 单测 + `docker compose config` 的变量校验。后者防的是那种只在换机器部署时才炸、本地「起过一次就再也不报」的问题。
- **console**：类型检查 → 构建 → 扫一遍产物里有没有外部地址。
- **plugin**：打包 → 冒烟。
- **image**：仅 PR 上运行，用同一份 Dockerfile 验镜像能否构建。

镜像的构建与推送全部在 `docker.yml` 里完成，本地与服务器都不需要访问 Docker Hub。

## 发布

两个工作流分工，均由 GitHub Actions 执行，本地无需 Docker 或 npm 凭据：

| 工作流 | 触发 | 动作 |
| --- | --- | --- |
| `docker.yml` | push 到 `main` | 构建并推送 `edge` 与 `sha-xxxxxxx` |
| | 推 `v*` tag | 构建并推送 `1.2.3`、`1.2`、`latest`、`sha-xxxxxxx` |
| `release.yml` | 推 `v*` tag | `npm publish --provenance` |

两个镜像都构建 linux/amd64 与 linux/arm64，并同时推到两处：

| 镜像 | Docker Hub | GHCR |
| --- | --- | --- |
| 服务 | `yejiu233/mnemo` | `ghcr.io/cmnyhh/mnemo` |
| 数据库 | `yejiu233/mnemo-db:18` | `ghcr.io/cmnyhh/mnemo-db:18` |

仓库 Secrets 需要三项：`DOCKERHUB_USERNAME`（Docker ID，不是邮箱——镜像名要拼成 `用户名/仓库`）、`DOCKERHUB_TOKEN`（Access Token，只授 `read/write/delete` 与 `push` 权限）、`NPM_TOKEN`（Granular Access Token，只绑这一个包）。

凭据只存在仓库的 Secrets 里，工作流只引用 `secrets.*`，仓库中没有任何明文。fork 出去的仓库读不到上游 Secrets，`publish` 与 `npm` 两个 job 还带 `github.repository` 判断，只在上游仓库执行。Docker Hub 两项缺失时整条流水线退回 GHCR。

配了 Hub 的话，镜像先推 Hub，再用 `buildx imagetools` 把多架构清单整份复制到 GHCR；复制失败只记一条告警，不拦发布。

推送完会再取一次匿名令牌校验 `mnemo` 与 `mnemo-db` 能否匿名拉取——仓库被设成私有会让这一步行红，而推送本身不会报错。

```bash
git tag v1.2.0 && git push origin v1.2.0
```

发版前同步三处版本号：`service/pom.xml`、`console/package.json`、`plugin/package.json`；`docker-compose.yml` 的默认镜像 tag 同步成新版本。

镜像标签来自 git ref，不由 `pom.xml` 决定，所以版本号只影响 jar 内的元数据与控制台显示。要回滚，把 compose 里的镜像 tag 指向旧版本即可。
