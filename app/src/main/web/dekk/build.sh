#!/usr/bin/env bash
#
# DEKK 网页版脚本构建脚本
#
# Via 浏览器 / Tampermonkey 只支持导入单文件用户脚本，所以源码按职责拆在 src/ 下，
# 这里按文件名顺序（00_ xxx、01_ xxx ...）拼成根目录的 dekk.js。
#
# 用法：bash build.sh
#
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/dekk.js"
SRC="$DIR/src"

if [ ! -d "$SRC" ]; then
  echo "缺少源码目录: $SRC" >&2
  exit 1
fi

: > "$OUT"
for f in "$SRC"/*.js; do
  [ -e "$f" ] || continue
  printf '/* ===================== %s ===================== */\n' "$(basename "$f")" >> "$OUT"
  cat "$f" >> "$OUT"
  printf '\n' >> "$OUT"
done

# 语法检查（有 node 就检查，没有就跳过）
if command -v node >/dev/null 2>&1; then
  if node --check "$OUT"; then
    echo "语法检查通过"
  else
    echo "语法检查失败" >&2
    exit 2
  fi
fi

echo "已生成: $OUT ($(wc -c < "$OUT") 字节, $(grep -c '' "$OUT") 行)"
