# Spring Boot 3 / JDK 21 服务镜像，内含构建好的控制台前端。
#
# 两段构建：
#   web     Node 里 vite build 出静态资源
#   build   Maven 把这些资源打进 jar 的 static/，再打可执行 jar
#
# 构建上下文是仓库根（compose 里已改），所以能同时看到 console/ 与 service/。
# 只想起 db 和 qdrant 时用 `docker compose up -d db qdrant`，
# 不要 `up -d`——那会连 service 一起构建。

FROM node:22-alpine AS web
WORKDIR /console

# 国内直连 npmjs 慢，默认走镜像；CI 在海外可以传 --build-arg NPM_REGISTRY=https://registry.npmjs.org
ARG NPM_REGISTRY=https://registry.npmmirror.com
# 不带 package-lock.json 进镜像：rolldown / esbuild 把平台二进制放在 optionalDependencies 里，
# 锁文件在 macOS 上生成时只记录了 darwin 那一份，Linux 上会「装成功、build 时缺 .node」
# （npm/cli#4828）。镜像里按 package.json 现算一次，版本范围都由 package.json 钉着。
COPY console/package.json ./
RUN npm config set registry "${NPM_REGISTRY}" \
 && npm install --no-audit --no-fund

COPY console/ ./
# 类型检查由 CI 与本地 npm run typecheck 负责，镜像只关心能不能产出资源
RUN npm run build


FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 先只拷 pom 与 settings，让依赖层能被缓存
COPY service/pom.xml service/maven-settings.xml ./
RUN mvn -B -q -s maven-settings.xml -DskipTests dependency:go-offline

# CI 里单测已经在独立 job 跑过了，镜像 job 传 -DskipTests 省一遍。
# 默认留空 = 照常跑测试，部署时的行为和以前一致。
ARG MVN_TEST_FLAG=

COPY service/src ./src
# 控制台产物落在 static/：仓库里不放构建结果，避免「改了源码忘了重新生成」
COPY --from=web /console/dist ./src/main/resources/static

RUN mvn -B -q -s maven-settings.xml package ${MVN_TEST_FLAG} \
 && mkdir -p /out \
 && find ./target -maxdepth 1 -name '*.jar' ! -name '*.original' -exec cp {} /out/app.jar \;


FROM eclipse-temurin:21-jre

RUN groupadd --system app \
 && useradd --system --gid app --no-create-home app

WORKDIR /app
COPY --from=build /out/app.jar /app/app.jar
# 附件目录要提前建好并归 app：docker 首次挂空卷时会用镜像里该路径的属主，
# 不预先 chown 的话容器以非 root 启动后写不进 /data/assets。
RUN mkdir -p /data/assets && chown -R app:app /app /data

USER app
EXPOSE 8080

# 堆写死 768m 不看 MaxRAMPercentage：容器默认取 limit 的 25%，
# 而 RSS 还要加 metaspace / 直接内存 / 线程栈，算漏了就被 OOM 杀成 exit 137。
# ExitOnOutOfMemoryError：真 OOM 就退出让 restart 拉起来，别僵在那里装活着。
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=128m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
