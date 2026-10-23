#!/usr/bin/env bash
# ============================================================
# FitSync — 推送到 GitHub
#
# 用法（在项目根目录执行）：
#   bash scripts/push-FitSync-to-github.sh <GitHub用户名> [Token] [仓库名]
#
# 例：
#   bash scripts/push-FitSync-to-github.sh yaoiwi
#   bash scripts/push-FitSync-to-github.sh yaoiwi ghp_xxxxxxxxxxxx
#   GH_TOKEN=ghp_xxxx bash scripts/push-FitSync-to-github.sh yaoiwi
#
# 说明：
#   - 不带 Token 时，git 会问密码，这时粘贴 Personal Access Token（不是登录密码）
#   - 带 Token 时，脚本把 token 临时写进 remote，推完立刻抹掉，不留在 .git/config
#   - 仓库名默认 FitSync，可作第三个参数覆盖
#   - 手机 Termux、电脑 Git Bash / macOS / Linux 都可用
# ============================================================

set -eu

GIT_USER="${1:-}"
TOKEN="${2:-${GH_TOKEN:-}}"
REPO="${3:-FitSync}"
BRANCH="main"

if [ -z "$GIT_USER" ]; then
  echo "用法: bash scripts/push-FitSync-to-github.sh <GitHub用户名> [Token] [仓库名]"
  echo "例:   bash scripts/push-FitSync-to-github.sh yaoiwi"
  exit 1
fi

# 定位项目根目录（脚本在 scripts/ 下）
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ"

echo "============================================"
echo " FitSync → GitHub"
echo "============================================"
echo "工程目录: $PROJ"
echo "目标仓库: https://github.com/${GIT_USER}/${REPO}"
echo

# 手机存储（/sdcard、/storage/emulated/0）上的仓库会被 git 判为 dubious ownership
git config --global --add safe.directory "$PROJ" 2>/dev/null || true
git config user.name "$GIT_USER" 2>/dev/null || true
git config user.email "${GIT_USER}@users.noreply.github.com" 2>/dev/null || true

if [ ! -d .git ]; then
  echo ">> 没有 .git，初始化新仓库"
  git init -b "$BRANCH" >/dev/null
fi

# 构建产物不该进仓库；local.properties 已被 .gitignore 忽略，不要删
rm -rf build */build .gradle .kotlin 2>/dev/null || true

git add -A
if ! git diff --cached --quiet; then
  COMMIT_MSG="chore: 推送前同步 $(date '+%Y-%m-%d %H:%M')"
  git commit -q -m "$COMMIT_MSG"
  echo ">> 已提交本地改动"
else
  echo ">> 没有待提交的改动"
fi

git branch -M "$BRANCH" >/dev/null 2>&1 || true

REMOTE_URL="https://github.com/${GIT_USER}/${REPO}.git"

if [ -n "$TOKEN" ]; then
  echo ">> 使用 Token 推送"
  PUSH_URL="https://${GIT_USER}:${TOKEN}@github.com/${GIT_USER}/${REPO}.git"
  if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "$PUSH_URL"
  else
    git remote add origin "$PUSH_URL"
  fi
  if ! git push -u origin "$BRANCH"; then
    echo
    echo "!! 推送失败，常见原因："
    echo "   1. Token 没有 repo 权限（推 CI 配置还需要 workflow 权限）"
    echo "   2. Token 已过期或被撤销"
    echo "   3. 仓库还没在网页上创建"
    echo "   4. 网络连不上 GitHub"
    exit 1
  fi
  # 推完把 token 从 .git/config 抹掉
  git remote set-url origin "$REMOTE_URL"
else
  echo ">> 未提供 Token，git 会询问凭据"
  echo "   用户名: ${GIT_USER}"
  echo "   密码:   粘贴 Personal Access Token（不是登录密码）"
  if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "$REMOTE_URL"
  else
    git remote add origin "$REMOTE_URL"
  fi
  if ! git push -u origin "$BRANCH"; then
    echo
    echo "!! 推送失败，可改用一次性命令："
    echo "   bash scripts/push-FitSync-to-github.sh ${GIT_USER} ghp_你的token"
    exit 1
  fi
fi

echo
echo "============================================"
echo " 推送完成"
echo " 仓库:      https://github.com/${GIT_USER}/${REPO}"
echo " 自动构建:  https://github.com/${GIT_USER}/${REPO}/actions"
echo "============================================"
echo "首次构建约 5-8 分钟，绿勾后进 Artifacts 下载 app-debug.apk"
