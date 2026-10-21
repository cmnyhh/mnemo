-- 只在 pgdata volume 为空、容器首次初始化时执行一次。
-- 之后再加脚本不会自动跑，扩展要手工补：
--   docker compose exec db psql -U memory -d memory -c 'create extension if not exists pg_textsearch'

create extension if not exists pg_textsearch;

-- 中文分词不在这里做。写 search_text 前由服务端 Java 分词器切好、空格分隔，
-- BM25 索引用 text_config='simple'。理由见 db/Dockerfile 顶部。
