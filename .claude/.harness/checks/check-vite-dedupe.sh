#!/usr/bin/env bash
# 阻断级：每个 app 的 vite.config 必须 resolve.dedupe 含 element-plus，
# 否则 pnpm 链接 @matecloud/ui 会让 element-plus 变成两个实例（locale/config 失效）。规则 05。
. "$(dirname "$0")/lib.sh"
h_title "vite resolve.dedupe 含 element-plus（每个 app）"

UI="$REPO_ROOT/mate-ui"
shopt -s nullglob 2>/dev/null || true
cfgs=("$UI"/apps/*/vite.config.ts)
[ "${#cfgs[@]}" -gt 0 ] || { h_ok "无 mate-ui/apps，跳过"; exit 0; }

bad=0
for cfg in "${cfgs[@]}"; do
  dir="$(dirname "$cfg")"
  rel="$(echo "$cfg" | sed "s|$REPO_ROOT/||")"
  pkg="$dir/package.json"
  # 只有同时依赖 element-plus 和 @matecloud/ui 的 app 才会触发双实例问题；
  # 其余（如 uni-app 移动端，无 EP）免检。
  if [ -f "$pkg" ] && grep -q '"element-plus"' "$pkg" && grep -q '"@matecloud/ui"' "$pkg"; then
    if grep -Eq "dedupe[^]]*element-plus" "$cfg"; then
      h_ok "$rel"
    else
      h_bad "$rel —— 缺 resolve.dedupe(['element-plus', ...])"
      bad=1
    fi
  else
    h_ok "${rel}（无 element-plus + @matecloud/ui，免检）"
  fi
done

[ "$bad" -eq 0 ] || exit 1
