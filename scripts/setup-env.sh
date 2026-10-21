#!/usr/bin/env bash
# 生成 .env：把 .env.example 里三个空着的凭据换成随机值，并把「用哪个账号登进去」打在屏幕上。
#
#   ./scripts/setup-env.sh            # .env 已存在时不动它
#   ./scripts/setup-env.sh --force    # 覆盖现有 .env（旧口令作废，数据不动）
#
# 已经跑过的部署不需要它：重跑只会换掉 .env 里的值，而控制台口令、PG 口令
# 在首次启动时就写进库里了，之后以库里为准。换口令请到控制台「系统」页。
set -eu
cd "$(dirname "$0")/.."

if [ ! -f .env.example ]; then
  echo "找不到 .env.example，请在仓库根目录运行" >&2
  exit 1
fi

if [ -f .env ] && [ "${1:-}" != "--force" ]; then
  echo ".env 已存在。要重新生成请加 --force（旧口令作废，数据不动）"
  exit 1
fi

# 去掉 base64 里会出现在 shell / URL 中的字符，省得复制粘贴时被解释
rand() { openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-"$1"; }

PG_PASS=$(rand 28)
QD_KEY=$(rand 28)
ADMIN_PASS=$(rand 16)

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    POSTGRES_PASSWORD=*) echo "POSTGRES_PASSWORD=$PG_PASS" ;;
    QDRANT_API_KEY=*) echo "QDRANT_API_KEY=$QD_KEY" ;;
    MEMORY_ADMIN_PASSWORD=*) echo "MEMORY_ADMIN_PASSWORD=$ADMIN_PASS" ;;
    *) echo "$line" ;;
  esac
done < .env.example > "$tmp"
mv "$tmp" .env
chmod 600 .env

cat <<EOF

已生成 .env（权限 600）。下面是这个部署的账号，口令只在这里出现一次：

  控制台        http://localhost:8090
                账号  admin
                口令  $ADMIN_PASS

  PostgreSQL    127.0.0.1:5432
                库    memory
                用户  memory
                口令  $PG_PASS

  Qdrant        127.0.0.1:6333
                鉴权头  api-key: $QD_KEY

  API 令牌      登录控制台 → 令牌 → 新建，插件里填那一串 mn_ 开头的令牌

下一步：docker compose up -d

EOF
