<p align="center">
  <img src="docs/screenshots/overview.png" alt="mnemo console" width="820">
</p>

<h1 align="center">mnemo</h1>

<p align="center">Self-hosted long-term memory for AI agents.</p>

<p align="center">
  <a href="https://hub.docker.com/r/yejiu233/mnemo"><img src="https://img.shields.io/docker/pulls/yejiu233/mnemo?label=docker%20pulls" alt="Docker pulls"></a>
  <a href="https://www.npmjs.com/package/mnemo-memory-core"><img src="https://img.shields.io/npm/v/mnemo-memory-core" alt="npm"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="License"></a>
</p>

<p align="center">
  English · <a href="README.zh-CN.md">简体中文</a>
</p>

---

Conversational assistants generally handle memory in one of two ways: they forget everything between sessions, or they replay the entire transcript until the context window and the bill become the limit. mnemo takes a third approach — it keeps a durable store of what was decided, and returns only the entries relevant to the current request.

It runs on your own hardware. Data stays in your PostgreSQL and Qdrant instances, and the model endpoints are yours to choose or to omit.

## Quick start

```bash
git clone https://github.com/cmnyhh/mnemo.git && cd mnemo
./scripts/setup-env.sh    # generates credentials and prints them once
docker compose up -d
```

The setup script prints the following:

| Entry | Address | Account | Secret |
| --- | --- | --- | --- |
| Console | <http://localhost:8090> | `admin` | generated, shown once |
| PostgreSQL | `127.0.0.1:5432` | `memory` (database `memory`) | `POSTGRES_PASSWORD` |
| Qdrant | `127.0.0.1:6333` | none | header `api-key: $QDRANT_API_KEY` |
| Memory API | `/v1/memory/*` | none | `mn_…` token issued in the console |

All secrets live in `.env`. Editing `.env` after the first start does not reset the console password: it is written to the database on first boot and changed from the console afterwards. To configure manually instead, follow the comments in `.env.example`.

Images are built and published by GitHub Actions to [Docker Hub](https://hub.docker.com/r/yejiu233/mnemo), which is what `docker compose up` pulls by default; the same tags are mirrored to GHCR for networks that cannot reach Docker Hub — set `MNEMO_IMAGE` / `MNEMO_DB_IMAGE` to `ghcr.io/cmnyhh/mnemo:1.2.0` and `ghcr.io/cmnyhh/mnemo-db:18` to switch. To build from source instead, add the build overlay — the first build takes roughly ten minutes:

```bash
docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build
```

Open <http://localhost:8090>, sign in, issue a token on the **Tokens** page, then use **Recall** to run a query.

Read and write through the API:

```bash
TOKEN=mn_your_token

curl -X POST http://localhost:8090/v1/memory/ingest \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"kind":"fact","narrative":"The service listens on 8090 behind an HTTPS reverse proxy","importance":0.8}'

curl -X POST http://localhost:8090/v1/memory/search \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"query":"which port does the service use"}'
```

The second call returns the entry just written, together with its score, the retrieval path that produced it, and an `injection` block ready to be placed in a prompt.

## Connect an agent

```bash
npm install mnemo-memory-core
```

Without a plugin host, call the API directly:

```bash
curl -s -X POST https://<your-host>/v1/memory/search \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"query":"the deployment plan we agreed on"}' | jq -r .injection
```

The plugin ([`plugin/`](plugin/README.md)) writes after each turn, injects before the next one, and registers recall as a tool the model can call on its own.

## Capabilities

**Durable entries, not transcripts.** Conversations are reduced to discrete facts and preferences at write time, each with its own subject, validity and source. Entries can be superseded, forgotten, or pinned without touching the rest of the store.

**Recall that can decline.** Every request is scored, and when the best candidate does not clear the threshold, nothing is injected. A missing memory costs less than a wrong one.

**Tiered visibility.** Pinned entries are injected every turn, working-set entries expire with the session, the long-term tier is retrieved by default, and archived entries stay out of results until explicitly requested.

**Memory is data, not instructions.** Injected content is wrapped and labelled as historical record. Content arriving from external sources is downgraded and marked, so text that reads like a command does not acquire authority.

**Observed, not guessed.** Recall latency is reported per stage — embedding, keyword, vector, rerank — and exported at `/internal/metrics` for Prometheus; every console action lands in an audit log with actor, target and source address.

**Derived indexes.** PostgreSQL holds the record; vectors and full-text indexes can be rebuilt from it at any time, including after a model or dimension change.

**Retained history.** Superseded and forgotten entries remain queryable in the console under their own status. Their vector copies can be reclaimed from **System → Storage** without deleting the entries.

**One deployment, one tenant.** No simulated multi-tenancy. Tokens can be revoked or given an expiry, individual memories can be forgotten, and console credentials are independent of API tokens.

## Scope

A single mnemo instance serves one team on one host. It is not a distributed system, has no built-in replication, and does not partition data by end user. Deployments that need per-user isolation should run one instance per tenant.

## Console

The web console covers the full lifecycle without a CLI: browse entries, run recall tests, edit model configuration, issue tokens, rebuild indexes, and inspect storage usage.

<table>
  <tr>
    <td><img src="docs/screenshots/records.png" alt="Memory records"></td>
    <td><img src="docs/screenshots/recall.png" alt="Recall test"></td>
  </tr>
  <tr>
    <td align="center">Records: origin turn and tier for every entry</td>
    <td align="center">Recall test: scores, channels, injection block</td>
  </tr>
</table>

## Requirements

Two vCPUs and 4 GB of RAM are sufficient. The three containers use about 360 MB at idle, and 30,000–50,000 entries remain comfortable. All state lives in Docker volumes; only PostgreSQL needs to be backed up. **System → Storage** reports the size of the database, the vector copies, the attachments, and the remaining disk.

Recall quality depends on two optional OpenAI-compatible endpoints: embeddings and reranking. An extraction model is also optional, and enables fact-level writes instead of paragraph splitting. None of the three is required — the service starts, accepts writes, and falls back to keyword retrieval without them.

## Documentation

| | |
| --- | --- |
| [Design](docs/design.md) | Retrieval, extraction, tiers, trust boundaries, data model |
| [Operations](docs/operations.md) | Configuration, console, tokens, backup, reverse proxy |
| [API](docs/api.md) | Every `/v1/memory/*` endpoint |
| [Development](docs/development.md) | Local setup, tests, builds, releases |
| [Plugin](plugin/README.md) | Installation, options, behaviour |

The `docs/` pages are currently written in Chinese.

## License

MIT
