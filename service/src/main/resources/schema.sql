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

-- ---- 程序性记忆：L1 轨迹 → L2 策略 → L3 世界模型 → Skill ----
--
-- 叙述性记忆回答「发生过什么」，这几张表回答「下次怎么做」。四层各存各的，
-- 不塞进 memory_item：一个刻意设计的约束是，Skill 的正文只由「成功的轨迹」生成，
-- 而轨迹本身可能含投毒内容，所以它在结晶前要过一遍 WriteReview（见 ProcedureService）。
--
-- L1 一条轨迹 = 一个目标 + 若干步（动作 / 观察 / 反思 / 局部价值）。
-- 关闭时才回传信用（credit），因为「这一步到底有没有用」要等终局奖励出来才知道。
create table if not exists proc_trajectory (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    text not null,
  user_id      text not null default '',
  project_id   text,
  session_id   text,
  -- 目标原文给人看，goal_key 是归一化后的聚类签名：「同一类任务」靠它归并
  goal         text not null,
  goal_key     text not null,
  status       text not null default 'open'
                 check (status in ('open','succeeded','failed','abandoned')),
  -- 终局奖励 0..1，调用方可给；不给时成功记 1、失败记 0
  reward       real,
  step_count   integer not null default 0,
  -- 整条轨迹的反思：成功/失败到底因为什么。它参与策略的 when_to_use 与 pitfalls
  reflection   text,
  strategy_id  uuid,
  started_at   timestamptz not null default now(),
  closed_at    timestamptz
);

create index if not exists idx_traj_goal
  on proc_trajectory (tenant_id, goal_key, closed_at desc);
create index if not exists idx_traj_recent
  on proc_trajectory (tenant_id, started_at desc);

-- 局部价值 value 由调用方按步给（0..1，可空）；reflection_weight 由文本启发式算出；
-- credit 是回传后的信用，正数=这一步对结果有贡献，负数=这一步是失败的原因。
create table if not exists proc_step (
  id                bigserial primary key,
  tenant_id         text not null,
  trajectory_id     uuid not null references proc_trajectory(id) on delete cascade,
  idx               integer not null,
  action            text not null,
  observation       text not null default '',
  reflection        text,
  value             real,
  reflection_weight real,
  credit            real,
  created_at        timestamptz not null default now(),
  unique (trajectory_id, idx)
);

create index if not exists idx_step_traj on proc_step (trajectory_id, idx);

-- L2 策略：同一 goal_key 下反复成功的路径。steps 是 [{order,action,why,expect}]，
-- pitfalls 来自失败轨迹里信用为负的步。support/success_rate 是它值不值得信的全部依据。
create table if not exists proc_strategy (
  id           uuid primary key default gen_random_uuid(),
  tenant_id    text not null,
  goal_key     text not null,
  name         text not null,
  goal         text not null,
  when_to_use  text not null,
  steps        jsonb not null default '[]'::jsonb,
  pitfalls     jsonb not null default '[]'::jsonb,
  support      integer not null default 0,
  success_rate real not null default 0,
  mean_return  real not null default 0,
  status       text not null default 'candidate'
                 check (status in ('candidate','active','quarantined','retired')),
  skill_id     uuid,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  unique (tenant_id, goal_key)
);

-- L3 世界模型：动作 → 预期观察。它不是「事实」，是「在此类任务里这样做通常会看到什么」，
-- 所以用 support/contradictions 表达置信度，被反例推翻就降权，而不是直接删掉。
-- key = md5(subject|action|expectation)：三段都是长文本，直接建唯一索引会超 btree 行宽。
create table if not exists proc_world (
  id             uuid primary key default gen_random_uuid(),
  tenant_id      text not null,
  subject        text not null,
  action         text not null,
  expectation    text not null,
  dedupe_key     text not null,
  confidence     real not null default 0.5,
  support        integer not null default 1,
  contradictions integer not null default 0,
  last_seen_at   timestamptz not null default now(),
  created_at     timestamptz not null default now(),
  unique (tenant_id, dedupe_key)
);

create index if not exists idx_world_subject
  on proc_world (tenant_id, subject, confidence desc);

-- 结晶产物：一份可直接给 agent 读的 SKILL.md。同一份技能会因新证据升版本，
-- 旧版本保留（status=retired），因为「它是怎么一步步变好的」本身值得留痕。
create table if not exists proc_skill (
  id          uuid primary key default gen_random_uuid(),
  tenant_id   text not null,
  name        text not null,
  title       text not null,
  description text not null,
  body        text not null,
  version     integer not null default 1,
  strategy_id uuid,
  evidence    jsonb not null default '{}'::jsonb,
  status      text not null default 'active' check (status in ('active','retired')),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  unique (tenant_id, name, version)
);

create index if not exists idx_skill_active
  on proc_skill (tenant_id, updated_at desc) where status = 'active';

alter table proc_trajectory enable row level security;
alter table proc_step enable row level security;
alter table proc_strategy enable row level security;
alter table proc_world enable row level security;
alter table proc_skill enable row level security;

drop policy if exists tenant_isolation on proc_trajectory;
create policy tenant_isolation on proc_trajectory
  using (tenant_id = current_setting('app.tenant_id', true))
  with check (tenant_id = current_setting('app.tenant_id', true));

drop policy if exists tenant_isolation on proc_step;
create policy tenant_isolation on proc_step
  using (tenant_id = current_setting('app.tenant_id', true))
  with check (tenant_id = current_setting('app.tenant_id', true));

drop policy if exists tenant_isolation on proc_strategy;
create policy tenant_isolation on proc_strategy
  using (tenant_id = current_setting('app.tenant_id', true))
  with check (tenant_id = current_setting('app.tenant_id', true));

drop policy if exists tenant_isolation on proc_world;
create policy tenant_isolation on proc_world
  using (tenant_id = current_setting('app.tenant_id', true))
  with check (tenant_id = current_setting('app.tenant_id', true));

drop policy if exists tenant_isolation on proc_skill;
create policy tenant_isolation on proc_skill
  using (tenant_id = current_setting('app.tenant_id', true))
  with check (tenant_id = current_setting('app.tenant_id', true));

grant select, insert, update, delete
  on proc_trajectory, proc_step, proc_strategy, proc_world, proc_skill to memory_app;
grant usage, select on sequence proc_step_id_seq to memory_app;
