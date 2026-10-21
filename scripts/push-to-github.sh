#!/data/data/com.termux/files/usr/bin/bash
# ============================================================
# FitGarminSync 一键推送到 GitHub
# 用法：
#   1) Termux 里执行：pkg install git -y
#   2) 改下面两行：GIT_USER / REPO
#   3) bash push.sh
# ============================================================

set -u

GIT_USER="你的GitHub用户名"
REPO="FitGarminSync"
BRANCH="main"

# 自动定位到脚本所在目录的上一级（工程根），换存放位置也不用改
PROJ="$(cd "$(dirname "$0")/.." && pwd)"

cd "$PROJ" || { echo "工程目录不存在: $PROJ"; exit 1; }

# git 在 sdcard 上会有权限警告，这里绕过
git config --global --add safe.directory "$PROJ"
git config --global user.name  "$GIT_USER"
git config --global user.email "$GIT_USER@users.noreply.github.com"

if [ ! -d .git ]; then
  echo ">> 初始化仓库"
  git init
  git checkout -b "$BRANCH" 2>/dev/null || git branch -M "$BRANCH"
fi

echo ">> 清理 Gradle 生成物（避免推上去几个 G）"
rm -rf .gradle build */build local.properties

echo ">> 暂存所有文件"
git add -A

echo ">> 提交"
git commit -m "build: 初始化工程 + GitHub Actions 在线构建" || echo "（没有新改动）"

if ! git remote get-url origin >/dev/null 2>&1; then
  echo ">> 添加 remote"
  git remote add origin "https://github.com/${GIT_USER}/${REPO}.git"
fi

echo ">> 推送（会要求输入用户名 + Personal Access Token 作为密码）"
git branch -M "$BRANCH"
git push -u origin "$BRANCH"

echo
echo "============================================"
echo "推送完成。现在去："
echo "https://github.com/${GIT_USER}/${REPO}/actions"
echo "点左侧 'Build Debug APK' -> 'Run workflow'"
echo "============================================"