#!/usr/bin/env bash
# 备份 PostgreSQL（唯一真相源）。
#
# Qdrant 不备份：它里面只有可重建的派生数据，丢了从 PG 回填即可。
# 反过来说，embedding 能重算、原始对话不能——所以这里备份的是 PG，
# 优先级别搞反。
#
# 用法：
#   ./scripts/backup.sh
#   BACKUP_DIR=/data/backups KEEP_DAYS=30 ./scripts/backup.sh
#
# 定时（每天 3:20）：
#   20 3 * * * /path/to/mnemo/scripts/backup.sh >> /var/log/mnemo-backup.log 2>&1

set -euo pipefail

cd "$(dirname "$0")/.."

BACKUP_DIR="${BACKUP_DIR:-./backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="${BACKUP_DIR}/memory-${STAMP}.dump"

mkdir -p "$BACKUP_DIR"

if ! docker compose ps --status running --services | grep -qx db; then
  echo "db 容器没在跑，跳过" >&2
  exit 1
fi

# -Fc 是自定义格式：支持选择性恢复、并行恢复，比纯 SQL 小
docker compose exec -T db pg_dump -U memory -d memory -Fc > "$FILE"

if [ ! -s "$FILE" ]; then
  echo "备份文件是空的，删掉并报错" >&2
  rm -f "$FILE"
  exit 1
fi

# 能读回来才算备份成功，只看文件大小会漏掉截断的 dump
docker compose exec -T db pg_restore --list < "$FILE" > /dev/null

echo "ok $(du -h "$FILE" | cut -f1) $FILE"

find "$BACKUP_DIR" -maxdepth 1 -name 'memory-*.dump' -mtime "+${KEEP_DAYS}" -print -delete
