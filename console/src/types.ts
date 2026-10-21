/** 服务端契约。字段名与 /admin/api/* 的 JSON 一一对应，不做额外映射。 */

export interface SessionInfo {
  authenticated: boolean
  consoleEnabled: boolean
  passwordProvided?: boolean
  sessionHours?: number
  csrfToken?: string
  username?: string
  expiresAt?: string
}

export interface ServiceInfo {
  name: string
  version: string
  startedAt: string
  uptimeSec: number
  heapUsedMb: number
  heapMaxMb: number
  javaVersion: string
  tenant: string
}

export interface VectorInfo {
  collection?: string
  dims?: number | null
  collectionDims?: number | null
  points?: number
  error?: string | null
}

export interface SpaceInfo {
  spaceId?: string
  provider?: string
  model?: string
  dims?: number
  fingerprint?: string
  reindexedFingerprint?: string | null
  requiresReindex?: boolean
}

export interface SessionRow {
  sessionId: string
  count: number
  lastAt?: string
}

export interface Stats {
  total?: number
  active?: number
  sessions?: number
  byKind?: { kind: string; count: number }[]
  byTier?: { tier: string; count: number }[]
}

export interface MediaStats {
  assets: number
  byKind?: Record<string, number>
}

/** /admin/api/storage：磁盘与数据量账目。bytes 一律是字节，前端换算显示。 */
export interface StorageInfo {
  postgres?: {
    bytes?: number
    items?: number
    active?: number
    retained?: number
    byStatus?: Record<string, number>
    error?: string
  }
  vectors?: {
    collection?: string
    dims?: number | null
    points?: number
    active?: number
    retained?: number
    bytes?: number
    error?: string | null
  }
  assets?: {
    files?: number
    records?: number
    bytes?: number
    dir?: string | null
    error?: string
  }
  disk?: {
    total?: number
    usable?: number
    error?: string
  }
}

export interface CleanupResult {
  /** 处理过的条目数（不等于删掉的向量点数：软删的条目早就没有向量了） */
  records: number
  deleted: number
  failed: number
  pointsRemoved?: number | null
  pointsAfter?: number
  elapsedMs: number
  note?: string
}

export interface Overview {
  service: ServiceInfo
  stats: Stats
  vector: VectorInfo
  space: SpaceInfo
  requiresReindex?: boolean
  reindex?: ReindexState | null
  recentSessions?: SessionRow[]
  configRevision?: number
  adminSessions?: number
  media?: MediaStats
}

export interface AdminRecord {
  id: string
  sessionId?: string | null
  turn?: number | null
  kind: string
  scope?: string
  tier?: string
  narrative: string
  status: string
  chars?: number
  createdAt?: string
  validFrom?: string
  validTo?: string | null
  accessCount?: number
}

export interface RecordsPage {
  total: number
  page: number
  size: number
  pages: number
  items: AdminRecord[]
}

export type ConfigType = 'ENUM' | 'INT' | 'DOUBLE' | 'STRING' | 'SECRET'

export interface ConfigField {
  key: string
  group: string
  label: string
  type: ConfigType
  options?: string[]
  value?: string | number | null
  masked?: string
  fallback?: string | number | null
  min?: number | null
  max?: number | null
  help?: string
  placeholder?: string
  secret?: boolean
  restart?: boolean
  affectsVectors?: boolean
}

export interface ConfigResponse {
  revision: number
  updatedAt?: string | null
  loaded?: boolean
  fields: ConfigField[]
  overridden?: string[]
  space?: SpaceInfo
  vector?: VectorInfo
  reindex?: ReindexState | null
  result?: { note?: string; requiresReindex?: boolean; revision?: number }
}

export interface ReindexState {
  status?: 'idle' | 'running' | 'done' | 'failed' | string
  processed: number
  total: number
  embedded: number
  failed: number
  message?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

export interface RecallHit {
  id?: string
  score: number
  narrative: string
  createdAt?: string
  bm25Rank?: number | null
  denseRank?: number | null
}

export interface RecallResult {
  route: string
  elapsedMs: number
  count: number
  abstained: boolean
  degradeReason?: string | null
  hits?: RecallHit[]
  injection?: string
  /** 本轮精排状态：off / skipped / applied / failed */
  rerank?: string | null
}

export interface DependencyProbe {
  ok?: boolean
  error?: string
  dims?: number | null
  points?: number
  provider?: string
  model?: string
  actualDims?: number
  latencyMs?: number
}

export interface Dependencies {
  db: DependencyProbe
  qdrant: DependencyProbe
  embedding: DependencyProbe
}

export interface EmbeddingProbe {
  provider?: string
  model?: string
  dims?: number
  ok?: boolean
  actualDims?: number
  latencyMs?: number
  sample?: number[]
  warning?: string
  error?: string
}

export interface RerankProbe {
  provider?: string
  model?: string
  ok?: boolean
  skipped?: boolean
  note?: string
  latencyMs?: number
  scores?: number[]
  spread?: number
  warning?: string
  error?: string
}

/** 令牌行。服务端从不回传明文，只有 prefix 是可见的。 */
export interface ApiTokenRow {
  id: string
  name: string
  prefix: string
  tenant: string
  state: 'active' | 'expired' | 'revoked'
  createdAt?: string | null
  lastUsedAt?: string | null
  expiresAt?: string | null
  revokedAt?: string | null
}

export interface ApiTokenList {
  items: ApiTokenRow[]
  tenant: string
}

export interface CreatedToken {
  token: string
  item: ApiTokenRow
  note: string
}

/** 一段链路的延迟分布（毫秒）。数值都由服务端四舍五入到 0.1。 */
export interface LatencyStats {
  count: number
  meanMs: number
  p50Ms: number
  p95Ms: number
  p99Ms: number
  maxMs: number
}

/** 分阶段耗时：kind = search / ingest，stage = embed / bm25 / dense / rerank / extract。 */
export interface StageStat {
  kind: string
  stage: string
  count: number
  p50Ms: number
  p95Ms: number
  maxMs: number
}

export interface MetricsSnapshot {
  search: LatencyStats
  ingest: LatencyStats
  stages: StageStat[]
  outcomes: Record<string, number>
  rerank: Record<string, number>
  degrade: Record<string, number>
  review: Record<string, number>
  scrapePath: string
  scrapeNote?: string
}

export interface AuditEntry {
  id: number
  at?: string | null
  actor: string
  action: string
  target?: string | null
  outcome: string
  ip?: string | null
  detail?: unknown
}

export interface AuditList {
  items: AuditEntry[]
  actions: string[]
  retentionDays: number
}
