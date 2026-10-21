# PostgreSQL 18 + pg_textsearch (BM25)
#
# 只装 pg_textsearch：纯 make、无 submodule，编起来干净。
#
# 不用 pg_jieba 的原因：它走 CMake，依赖 libjieba -> cppjieba -> limonp 两层
# submodule，而 GitHub 的 tarball 不含 submodule、这台机的 github.com 又不通
# （codeload 通但 submodule 拉不到）。中文分词改到应用层做——写 search_text
# 之前先用 Java 分词器切成空格分隔，索引 text_config 用 'simple'。
# 「文档与查询同一套分词器」这个目标在应用层同样成立，而且能单测。

FROM postgres:18

ARG PG_TEXTSEARCH_REF=main

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      build-essential \
      postgresql-server-dev-18 \
      curl \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# 走 codeload 而不是 git clone：这台机 github.com 超时，codeload 可达。
# 下载约 1.2MB / 30s，慢但稳定。
RUN mkdir -p /tmp/src \
 && curl -fsSL "https://codeload.github.com/timescale/pg_textsearch/tar.gz/refs/heads/${PG_TEXTSEARCH_REF}" \
      | tar xz -C /tmp/src --strip-components=1 \
 && make -C /tmp/src \
 && make -C /tmp/src install \
 && rm -rf /tmp/src

CMD ["postgres"]
