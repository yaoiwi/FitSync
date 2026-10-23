#!/usr/bin/env bash
# ============================================================
# FitSync 推送到 GitHub（手机 Termux / 电脑 通用）
#
# 用法：
#   bash scripts/push-to-github.sh <GitHub用户名> [仓库名] [PersonalAccessToken]
#
# 例：
#   bash scripts/push-to-github.sh zhangsan
#   bash scripts/push-to-github.sh zhangsan FitSync ghp_xxxxxxxx
#   GH_TOKEN=ghp_xxxxxxxx bash scripts/push-to-github.sh zhangsan
#
# 前提（二选一）：
#   A. 装了 gh 并已登录  -> 脚本会自动建仓库
#   B. 已在网页上建好空仓库 -> 脚本只推送
#
# 不传 token 时，git 会在命令行问密码，粘贴 PAT 即可。
# 传了 token 时，脚本会自动拼进 remote、推完再自动抹掉，不会留在 .git/config 里。
# ============================================================

set -eu

GIT_USER="${1:-}"
REPO="${2:-FitSync}"
TOKEN="${3:-${GH_TOKEN:-}}"
BRANCH="main"

if [ -z "$GIT_USER" ]; then
  echo "用法: bash scripts/push-to-github.sh <GitHub用户名> [仓库名] [Token]"
  exit 1
fi

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ"

echo ">> 工程目录: $PROJ"
echo ">> 目标仓库: https://github.com/${GIT_USER}/${REPO}"

# 手机存储（/sdcard、/storage/emulated/0）上的仓库会被 git 判为 dubious ownership
git config --global --add safe.directory "$PROJ" 2>/dev/null || true
git config user.name  "$GIT_USER" 2>/dev/null || true
git config user.email "${GIT_USER}@users.noreply.github.com" 2>/dev/null || true

if [ ! -d .git ]; then
  echo ">> 本地没有 .git，初始化"
  git init -b "$BRANCH"
fi

# 构建产物不该进仓库；local.properties 已被 .gitignore 忽略，不要删
rm -rf build */build .gradle .kotlin 2>/dev/null || true

git add -A
if ! git diff --cached --quiet; then
  echo ">> 提交新改动"
  git commit -m "chore: sync before push"
else
  echo ">> 没有待提交的改动"
fi

git branch -M "$BRANCH" 2>/dev/null || true

push_with_gh() {
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    if gh repo view "${GIT_USER}/${REPO}" >/dev/null 2>&1; then
      echo ">> 仓库已存在，直接推送"
      if git remote get-url origin >/dev/null 2>&1; then
        git remote set-url origin "https://github.com/${GIT_USER}/${REPO}.git"
      else
        git remote add origin "https://github.com/${GIT_USER}/${REPO}.git"
      fi
      git push -u origin "$BRANCH"
    else
      gh repo create "$REPO" --public --source=. --remote=origin --push
    fi
    return 0
  fi
  return 1
}

if [ -n "$TOKEN" ]; then
  echo ">> 使用传入的 Token 推送（不会留在 .git/config）"
  PUSH_URL="https://${GIT_USER}:${TOKEN}@github.com/${GIT_USER}/${REPO}.git"
  if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "$PUSH_URL"
  else
    git remote add origin "$PUSH_URL"
  fi
  # 只影响本次推送，避免 token 被写进 .git/config
  if ! git -c http.extraheader= push -u "$PUSH_URL" "$BRANCH"; then
    echo "!! 推送失败：检查 token 是否有 repo 权限、仓库是否已创建"
    exit 1
  fi
  git remote set-url origin "https://github.com/${GIT_USER}/${REPO}.git"
elif push_with_gh; then
  echo ">> 已用 gh 推送"
else
  echo ">> 走普通 git 推送"
  echo "   请确认网页上已创建空仓库（不要勾 README / .gitignore / license）"
  if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "https://github.com/${GIT_USER}/${REPO}.git"
  else
    git remote add origin "https://github.com/${GIT_USER}/${REPO}.git"
  fi
  echo "   用户名填 ${GIT_USER}，密码处粘贴 Personal Access Token"
  if ! git push -u origin "$BRANCH"; then
    echo "!! 推送失败：多半是认证或网络问题"
    echo "   认证：token 需要勾 repo 权限，且不要用登录密码"
    echo "   网络：GitHub 在国内可能连不上，挂代理后再试，或改用"
    echo "         GH_TOKEN=你的token bash scripts/push-to-github.sh ${GIT_USER}"
    exit 1
  fi
fi

echo
echo "============================================"
echo "推送完成，去这里看自动构建："
echo "https://github.com/${GIT_USER}/${REPO}/actions"
echo "构建完成后在 Artifacts 里下载 app-debug"
echo "============================================"
