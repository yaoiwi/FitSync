#!/usr/bin/env bash
# ============================================================
# FitSync 推送到 GitHub
#
# 用法：
#   bash scripts/push-to-github.sh <GitHub用户名> [仓库名]
#
# 例：
#   bash scripts/push-to-github.sh zhangsan
#   bash scripts/push-to-github.sh zhangsan FitSync
#
# 前提（二选一）：
#   A. 装了 gh 并已登录  -> 脚本会自动建仓库
#   B. 已在网页上建好空仓库 -> 脚本只推送，密码处粘贴 Personal Access Token
# ============================================================

set -eu

GIT_USER="${1:-}"
REPO="${2:-FitSync}"
BRANCH="main"

if [ -z "$GIT_USER" ]; then
  echo "用法: bash scripts/push-to-github.sh <GitHub用户名> [仓库名]"
  exit 1
fi

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ"

echo ">> 工程目录: $PROJ"
echo ">> 目标仓库: https://github.com/${GIT_USER}/${REPO}"

# sdcard 上的仓库 git 会报 dubious ownership，这里放行当前目录
git config --global --add safe.directory "$PROJ" 2>/dev/null || true
git config user.name  "$GIT_USER" 2>/dev/null || true
git config user.email "${GIT_USER}@users.noreply.github.com" 2>/dev/null || true

# 本地已有提交（仓库是打包好的），这里只补提交，不重新 init
if [ ! -d .git ]; then
  echo ">> 本地没有 .git，初始化"
  git init -b "$BRANCH"
fi

# build 产物不该进仓库；local.properties 已被 .gitignore 忽略，不要删（删了下次构建要重配 SDK 路径）
rm -rf build */build .gradle .kotlin 2>/dev/null || true

git add -A
if ! git diff --cached --quiet; then
  echo ">> 提交新改动"
  git commit -m "chore: sync before push"
else
  echo ">> 没有待提交的改动"
fi

git branch -M "$BRANCH" 2>/dev/null || true

# 优先用 gh（能顺带建仓库）；否则要求网页上已建好
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  echo ">> 检测到已登录的 gh，用 gh 建仓库并推送"
  if gh repo view "${GIT_USER}/${REPO}" >/dev/null 2>&1; then
    echo "   仓库已存在，直接推送"
    git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/${GIT_USER}/${REPO}.git"
    git push -u origin "$BRANCH"
  else
    gh repo create "$REPO" --public --source=. --remote=origin --push
  fi
else
  echo ">> 未检测到登录的 gh，走普通 git 推送"
  echo "   请确认网页上已创建空仓库（不要勾 README / .gitignore / license）"
  git remote get-url origin >/dev/null 2>&1 || git remote add origin "https://github.com/${GIT_USER}/${REPO}.git"
  echo "   提示：密码处粘贴 Personal Access Token（Settings -> Developer settings -> Tokens）"
  git push -u origin "$BRANCH"
fi

echo
echo "============================================"
echo "推送完成，去这里看自动构建："
echo "https://github.com/${GIT_USER}/${REPO}/actions"
echo "构建完成后在 Artifacts 里下载 app-debug"
echo "============================================"
