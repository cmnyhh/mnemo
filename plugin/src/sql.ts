/**
 * The SQL port and the schema the durable stores expect.
 *
 * The port is three lines on purpose: `pg`, `postgres.js`, and a WASM Postgres
 * used from tests all expose `query(text, params)`, and a wider interface would
 * make the adapter depend on one of them. The plugin stays dependency-free; the
 * host picks the driver.
 *
 * Two tables, one row per record and one per fact statement. Facts are rows
 * rather than a JSON column because their identity is the point: the current
 * value of `(session, predicate, subject)` is a uniqueness constraint, not
 * something to be re-derived by scanning JSON on every query.
 * @module @deepseek-ai/dsh-memory-core/sql
 */

/** A row as the driver returns it; column names are the ones in the schema. */
export type SqlRow = Readonly<Record<string, unknown>>

/** Minimal execution port, satisfied by any Postgres client. */
export interface Sql {
  /**
   * Run one statement.
   * @param text - SQL with `$1`-style placeholders.
   * @param params - positional parameters, in placeholder order.
   * @returns the rows the statement produced.
   */
  query<Row extends object = SqlRow>(
    text: string,
    params?: readonly unknown[],
  ): Promise<{ readonly rows: readonly Row[] }>
}

/**
 * Idempotent schema for both stores.
 *
 * `memory_fact_one_current` is the load-bearing line: it makes "one current
 * value per (session, predicate, subject)" a database invariant rather than a
 * convention the adapter is trusted to follow. `valid_to is null` in the
 * predicate is what lets retired rows accumulate beside it.
 */
export const MEMORY_SCHEMA = `
create table if not exists memory_record (
  seq bigint generated always as identity primary key,
  id text not null unique,
  session_id text not null,
  kind text not null,
  turn integer not null,
  body text not null,
  context text,
  created_at bigint not null,
  facts jsonb not null default '[]'::jsonb
);

create index if not exists memory_record_turn on memory_record (turn);
create index if not exists memory_record_session on memory_record (session_id, turn);

create table if not exists memory_fact (
  id text primary key,
  session_id text not null,
  predicate text not null,
  subject text not null default '',
  object text not null,
  valid_from integer not null,
  valid_to integer,
  recorded_at bigint not null
);

create index if not exists memory_fact_current
  on memory_fact (session_id, predicate) where valid_to is null;
create unique index if not exists memory_fact_one_current
  on memory_fact (session_id, predicate, subject) where valid_to is null;
create index if not exists memory_fact_retired
  on memory_fact (session_id, predicate, valid_to);
`

/**
 * Create the tables if they do not exist.
 *
 * Statements run one at a time: a multi-statement string is a convenience of
 * the simple query protocol, and a driver that only accepts one statement
 * (a WASM Postgres in tests, a pooled client in production) would fail on it.
 * @param sql - execution port.
 */
export async function migrateMemorySchema(sql: Sql): Promise<void> {
  for (const statement of MEMORY_SCHEMA.split(';')) {
    const trimmed = statement.trim()
    if (trimmed.length > 0) await sql.query(trimmed)
  }
}
