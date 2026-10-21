#!/usr/bin/env bash
# 建向量集合。幂等：已存在就只打印现状。
#
# 参数选择：向量落盘、HNSW 图留内存。
#   4096 维 × 4B = 16KB/点，是唯一的大头，交给 mmap + page cache；
#   HNSW 图约 200B/点（10 万条才 20MB），随机访问，必须留内存。
# 反过来配（向量在内存、图在磁盘）是这台 3G 的机器上最坏的组合。

set -euo pipefail

cd "$(dirname "$0")/.."

KEY="$(sed -n 's/^QDRANT_API_KEY=//p' .env)"
DIMS="$(sed -n 's/^EMBEDDING_DIMS=//p' .env)"
DIMS="${DIMS:-4096}"
COLLECTION="${COLLECTION:-memory}"
BASE="http://127.0.0.1:6333"

if [ -z "$KEY" ]; then
  echo "QDRANT_API_KEY 没配，先填 .env" >&2
  exit 1
fi

body=$(printf '{"vectors":{"size":%s,"distance":"Cosine","on_disk":true},"hnsw_config":{"on_disk":false,"m":16,"ef_construct":128},"optimizers_config":{"memmap_threshold":20000}}' "$DIMS")

if curl -sf -H "api-key: $KEY" "$BASE/collections/$COLLECTION" > /dev/null; then
  echo "collection $COLLECTION 已存在，不动它"
else
  curl -sf -X PUT \
    -H "api-key: $KEY" \
    -H 'content-type: application/json' \
    -d "$body" \
    "$BASE/collections/$COLLECTION" > /dev/null
  echo "created $COLLECTION (${DIMS}d, vectors on_disk, hnsw in memory)"
fi

curl -s -H "api-key: $KEY" "$BASE/collections/$COLLECTION" | head -c 500
echo
