-- 幂等 schema。服务启动时由 ResourceDatabasePopulator 执行，语句按分号切分，
-- 所以这里不写 DO $$ ... $$ 块（切分会切坏）。
--
-- 与设计稿 §4.2 的差异：
--   * 没有 create extension vector —— 向量在 Qdrant，PG 只存元数据
--   * 没有 pg_jieba、索引 text_config 用 simple —— 分词在 Java 侧做（见 Tokenizer）
--   * 排他约束写在 CREATE TABLE 内联，避免 ALTER TABLE ADD CONSTRAINT 不幂等

create extension if not exists pg_textsearch;
create extension if not exists btree_gist;
create extension if not exists pgcrypto;

create table if not exists memory_item (
  id               uuid primary key default gen_random_uuid(),
  tenant_id        text not null,
  -- 写下这条记录的令牌。只用于事后追溯（某把令牌泄露时按来源清理），不参与鉴权，
  -- 因此可空：控制台种子数据、迁移脚本、评测数据集都没有令牌。
  token_id         uuid,
  user_id          text not null default '',
  project_id       text,
  scope            text not null check (scope in ('user','project','global','agent')),
  kind             text not null check (kind in ('preference','fact','episode')),
  tier             text not null default 'T2' check (tier in ('T0','T1','T2','T3')),
  trust_level      text not null default 'medium' check (trust_level in ('high','medium','low')),

  subject_key      text,
  predicate        text,
  object_value     text,
  facts            jsonb not null default '{}'::jsonb,

  narrative        text not null,
  recall_hints     text[] not null default '{}',
  search_text      text not null,
  kw_tokens        text[] not null default '{}',

  source_kind      text not null check (source_kind in ('turn','tool','manual','import')),
  session_id       text,
  turn             integer,

  confidence       real not null default 0.70 check (confidence between 0 and 1),
  importance       real not null default 0.50 check (importance between 0 and 1),

  status           text not null default 'active'
                     check (status in ('active','superseded','archived','deleted')),
  supersedes_id    uuid references memory_item(id),

  valid_range      tstzrange not null,
  recorded_at      timestamptz not null default now(),

  content_hash     text not null,
  idem_key         text not null,

  cited_count      integer not null default 0,
  correction_count integer not null default 0,
  access_count     integer not null default 0,
  last_access_at   timestamptz,

  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),

  constraint uk_idem unique (tenant_id, idem_key),
  constraint ck_range check (not isempty(valid_range)),

  -- 同一主语同一谓词的有效事实，时间区间不许重叠。
  -- 这比「只允许一条 active」更强：两条 active 如果区间不重叠也不该并存。
  constraint ex_no_overlap exclude using gist (
    tenant_id with =,
    (coalesce(project_id, '')) with =,
    subject_key with =,
    predicate with =,
    valid_range with &&
  ) where (status = 'active' and subject_key is not null and predicate is not null)
);

-- 早期库里没有 token_id：create table if not exists 不会给已有表补列，必须单独 alter。
alter table memory_item add column if not exists token_id uuid;

-- 同一 (tenant, project, subject, predicate) 至多一条 active
create unique index if not exists uk_active_fact
  on memory_item (tenant_id, coalesce(project_id, ''), subject_key, predicate)
  where status = 'active' and subject_key is not null and predicate is not null;

-- BM25 原生倒排。text_config=simple：search_text 已由 Java 切好、空格分隔。
create index if not exists idx_mem_bm25
  on memory_item using bm25 (search_text) with (text_config = 'simple');

create index if not exists idx_recall on memory_item (tenant_id, project_id, scope, status, tier);
create index if not exists idx_window on memory_item using gist (valid_range);
create index if not exists idx_hash on memory_item (tenant_id, content_hash);
create index if not exists idx_session on memory_item (tenant_id, session_id, created_at desc);
-- 按令牌溯源：泄露时一条 SQL 就能列出（并批量退休）某把令牌写过什么。
create index if not exists idx_item_token on memory_item (tenant_id, token_id) where token_id is not null;

-- 向量空间注册表：一个模型一行，一行一个后端（设计稿 §4.8）
create table if not exists embedding_space (
  space_id      text primary key,
  backend       text not null default 'qdrant' check (backend in ('qdrant','pgvector')),
  backend_ref   text not null,
  provider      text not null,
  model         text not null,
  native_dim    integer not null check (native_dim between 1 and 65535),
  index_spec    jsonb not null default '{}'::jsonb,
  metric        text not null default 'cosine',
  normalize     boolean not null default true,
  is_active     boolean not null default false,
  created_at    timestamptz not null default now()
);

-- I8：任意时刻至多一个活跃空间。用「表达式索引 + 部分索引」表达唯一性，
-- 因为 is_active 是 boolean，普通唯一索引只会允许一行 true、一行 false。
--
-- 建索引之前先收敛历史数据：早期版本每次切换模型都追加一行并标 is_active=true，
-- 从不清旧的，于是库里会同时存在四五个「活跃」空间（0.7.x 之前就是这样）。
-- 保留最新创建的那一行，其余置 false——这和 seedSpace 的行为一致：启动时按当前配置
-- 写入/更新一行并置为活跃，最新的那行就是正在用的。
update embedding_space
   set is_active = false
 where is_active
   and space_id <> (select space_id from embedding_space where is_active
                     order by created_at desc, space_id limit 1);

create unique index if not exists uk_space_single_active
  on embedding_space ((true)) where is_active;

-- 控制台写入的配置覆盖值（单行）。基线始终是环境变量；这里只存「被改过的那几项」。
-- 存 JSONB 而不是逐列，是因为配置项会随版本增删，逐列要写迁移而 JSONB 不用。
create table if not exists app_config (
  id         boolean primary key default true check (id),
  revision   bigint not null default 1,
  values     jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now()
);

-- 与配置无关的运行时状态（例如最近一次重建索引用的向量指纹）
create table if not exists app_state (
  key   text primary key,
  value text not null
);

-- 控制台账号（单行）。只存 PBKDF2 哈希，不存明文。
create table if not exists admin_credential (
  id            boolean primary key default true check (id),
  username      text not null default 'admin',
  password_hash text not null,
  updated_at    timestamptz not null default now()
);

-- 老库补列：早期版本只有口令，升级后账号名默认 admin。
alter table admin_credential add column if not exists username text not null default 'admin';

-- API 令牌（0.9.1 起）。与控制台口令同一口径：**只存 sha256 哈希**，明文只在创建那一刻返回一次，
-- 之后任何接口都不再回显——包括创建它的那个会话重新打开页面。
--
-- **唯一来源就是这张表**：没有另设的固定令牌。行被全部撤销 / 过期后 /v1/* 一律 401，
-- 但控制台走的是口令 + 会话，仍然进得去，在那里签发一把新的即可恢复。
-- 0.9.2 删掉了原来的 MEMORY_API_TOKEN 引导令牌——它不出现在列表里、也不能撤销，
-- 等于一个看不见的后门，比「少一条恢复路径」更糟。
--
-- 不启用 RLS：业务角色 memory_app 没有这张表的任何权限，认证查询走的是表 owner 连接。
create table if not exists api_token (
  id           uuid primary key default gen_random_uuid(),
  name         text not null,
  token_hash   text not null unique,
  -- 明文前缀（mn_ + 8 位），用于在列表里认出「是哪一把」，不足以还原令牌
  prefix       text not null,
  tenant_id    text not null,
  created_by   text not null default 'console',
  created_at   timestamptz not null default now(),
  last_used_at timestamptz,
  expires_at   timestamptz,
  revoked_at   timestamptz,
  constraint ck_token_name check (length(btrim(name)) between 1 and 64)
);

create index if not exists idx_token_created on api_token (created_at desc);

-- 多模态附件。文件本体落在 memory.assets.dir 指向的卷上，这里只存元数据。
-- 一行 = 一次「附件挂在某条记忆上」的绑定；同一张图被两条记忆引用会有两行，
-- 但磁盘上只有一份文件（storage_key 由 sha256 决定）。
create table if not exists memory_asset (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   text not null,
  memory_id   uuid references memory_item(id),
  kind        text not null check (kind in ('image','audio','video','document')),
  mime_type   text not null,
  bytes       bigint not null check (bytes >= 0),
  sha256      text not null,
  storage_key text not null,
  width       integer,
  height      integer,
  -- 图注来自 VLM 或调用方；ocr_text 是图内文字。两者都进 BM25 索引，
  -- 否则「图里写着什么」这类问题永远命中不了这张图。
  caption     text,
  ocr_text    text,
  source_name text,
  created_at  timestamptz not null default now()
);

create index if not exists idx_asset_memory on memory_asset (tenant_id, memory_id);
create index if not exists idx_asset_sha on memory_asset (tenant_id, sha256);

-- 向量登记表：与后端无关，永远在 PG 里（设计稿 §4.8）。
-- Qdrant 是最终存放处，这张表只回答两个问题：「这条记忆的向量写过没有」
-- 和「写进去的是不是同一份」。没有它，向量丢了只能靠召回变差来发现。
create table if not exists memory_vector (
  item_id     uuid not null references memory_item(id) on delete cascade,
  space_id    text not null references embedding_space(space_id),
  dims        integer not null check (dims > 0),
  -- 向量内容的摘要（space + 归一化 float32 的小端字节）。不存向量也能对账。
  vec_hash    text not null,
  backend     text not null default 'qdrant',
  backend_ack boolean not null default false,
  tenant_id   text not null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  primary key (item_id, space_id)
);

create index if not exists idx_vector_pending
  on memory_vector (updated_at) where not backend_ack;
create index if not exists idx_vector_tenant on memory_vector (tenant_id, space_id);

-- ---- 行级安全（设计稿 §4.7）----
--
-- 表 owner（memory）默认绕过 RLS，所以策略本身不影响建表与管理操作；
-- 业务请求会 SET ROLE 到 memory_app，那时策略才生效。这就是「应用层过滤 +
-- 数据库兜底」的两道：代码漏了 tenant_id，第二道返回空集而不是别人的行。
--
-- current_setting 带 missing_ok=true：没设过变量时返回 NULL，比较结果也是 NULL
-- （不通过），所以「忘了设上下文」＝看不到任何行，不会退化成全放行。
alter table memory_item enable row level security;
alter table memory_asset enable row level security;
alter table memory_vector enable row level security;

drop policy if exists tenant_isolation on memory_item;
create policy tenant_isolation on memory_item
  using (tenant_id = current_setting('app.tenant_id', true))
  with check (tenant_id = current_setting('app.tenant_id', true));

drop policy if exists tenant_isolation on memory_asset;
create policy tenant_isolation on memory_asset
  using (tenant_id = current_setting('app.tenant_id', true))
  with check (tenant_id = current_setting('app.tenant_id', true));

drop policy if exists tenant_isolation on memory_vector;
create policy tenant_isolation on memory_vector
  using (tenant_id = current_setting('app.tenant_id', true))
  with check (tenant_id = current_setting('app.tenant_id', true));

-- 受限角色的权限刻意只给到记忆数据，不给 app_config / admin_credential：
-- 业务面没有任何理由读控制台口令哈希。
grant usage on schema public to memory_app;
grant select on embedding_space to memory_app;
grant select, insert, update, delete on memory_item, memory_asset, memory_vector to memory_app;

-- ---- 审计日志（控制台与治理动作留痕）----
--
-- 只记「谁在什么时候动了什么」，不记记忆正文：审计表本身不该成为第二份记忆库。
-- detail 里凡是口令 / 令牌 / api key 一律打码（见 AuditService.mask）。
--
-- 没有 RLS：写它的是控制台（表 owner 身份），而 memory_app 只需要能写自己那一份，
-- 读也只给控制台。单租户部署下多一层策略只会让人误以为它做了租户隔离。
create table if not exists audit_log (
  id          bigserial primary key,
  occurred_at timestamptz not null default now(),
  actor       text not null default 'system',
  action      text not null,
  target      text,
  outcome     text not null default 'ok',
  ip          text,
  tenant_id   text,
  detail      jsonb
);

create index if not exists idx_audit_occurred on audit_log (occurred_at desc);
create index if not exists idx_audit_action on audit_log (action, occurred_at desc);

grant select, insert, delete on audit_log to memory_app;
grant usage, select on sequence audit_log_id_seq to memory_app;
