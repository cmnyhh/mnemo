#!/usr/bin/env bash
# 修 dsh 插件「loaded without registering」。
#
# 症状（web boot 报错）：
#   @cmnyhh/dsh-mnemo: import failed: client-modules: could not load "@cmnyhh/dsh-mnemo"
#   ... already executed without registering "@cmnyhh/dsh-mnemo"
#   ... loaded without registering "@cmnyhh/dsh-mnemo" via __ModuleLoader__.load
#
# 根因：client.js 里 __ModuleLoader__.load({ id: ... }) 的 id 必须**逐字等于包名**。
# 加载器在 arrive() 里用 row.id 去 factories 里查，查不到就报这个错，而且不报「id 写错了」，
# 只说「loaded without registering」——看起来像包没装好，其实是名字对不上。
# npm 上 0.9.5 的 client.js 写的是老仓库名 @deepseek-ai/dsh-memory-core，所以装 0.9.5 必踩。
#
# 另一个坑：机器上存在多份副本时，坏的那份先执行，loader 就把它标记成「已执行」，
# 修好的那份随后执行也注不上——所以必须把所有副本一起替换，然后重启宿主进程清掉内存态。
#
# 用法（root，在目标机上）：
#   bash fix-dsh-plugin.sh            # 默认取 npm 上最新的 @cmnyhh/dsh-mnemo
#   bash fix-dsh-plugin.sh 0.9.6      # 指定版本
set -euo pipefail

PKG=${PKG:-@cmnyhh/dsh-mnemo}
VER=${1:-latest}

echo "== 1. 找出所有安装点 =="
mapfile -t DIRS < <(find / -xdev -type d -name "$(basename "$PKG")" -path "*node_modules*" 2>/dev/null)
if [ "${#DIRS[@]}" -eq 0 ]; then
  echo "没找到 $PKG 的安装目录。确认插件是装在这个 profile 里，还是根本没装上。" >&2
  exit 1
fi

bad=0
for d in "${DIRS[@]}"; do
  loaded=$(sed -n '2p' "$d/client.js" 2>/dev/null | tr -d '\t' | sed 's/^ *//')
  printf '  %-88s %s\n' "$d" "$loaded"
  case "$loaded" in
    *"$PKG"*) ;;
    *) bad=$((bad + 1)) ;;
  esac
done
echo "  共 ${#DIRS[@]} 份，其中 ${bad} 份 id 不对"

echo "== 2. 从 npm 取正确版本 =="
TMP=$(mktemp -d)
cd "$TMP"
npm pack "$PKG@$VER" >/dev/null 2>&1 || { echo "npm pack 失败：$PKG@$VER" >&2; exit 1; }
tar xzf ./*.tgz
GOOD=$(sed -n '2p' package/client.js | tr -d '\t' | sed 's/^ *//')
echo "  包内 id: $GOOD"
case "$GOOD" in
  *"$PKG"*) ;;
  *) echo "包内 id 与包名不一致，这个版本本身就是坏的，换一个版本" >&2; exit 1 ;;
esac

echo "== 3. 覆盖所有副本 =="
for d in "${DIRS[@]}"; do
  cp package/client.js "$d/client.js"
  cp package/index.js "$d/index.js"
  [ -f package/cordis.patch.yml ] && cp package/cordis.patch.yml "$d/cordis.patch.yml"
  echo "  fixed $d"
done

echo "== 4. 重启宿主进程 =="
# 内存里「已执行」的模块记录只有重启才清得掉；改文件不够。
for unit in dsh-web dsh; do
  if systemctl list-unit-files "$unit.service" >/dev/null 2>&1 \
     && systemctl is-enabled "$unit" >/dev/null 2>&1; then
    systemctl restart "$unit"
    sleep 3
    echo "  $(systemctl is-active "$unit")  $unit"
  fi
done
echo "完成。浏览器强刷（cmd/ctrl+shift+R）再看插件页。"
