#!/bin/sh
# =============================================================
# ApeAdmin-Spring *nix 守护启动脚本
#
# 关键机制：管理后台一键更新会按约定退出码 90 退出（新 jar 已就位），
# 本脚本检测到 90 后自动重新拉起，实现「自更新 + 自动重启」闭环。
# =============================================================

# 打包时由 package-release.sh 注入实际版本号
APP_VERSION="@VERSION@"
export APE_APP_VERSION="$APP_VERSION"

echo "ApeAdmin-Spring v$APP_VERSION 启动中... (退出码 90 = 更新完成自动重启)"
while true; do
  java -Dape.app.version="$APP_VERSION" -jar apeadmin.jar
  CODE=$?
  if [ "$CODE" -ne 90 ]; then
    echo "进程退出（code=$CODE），守护结束。"
    break
  fi
  echo "检测到更新退出码 90，1 秒后拉起新版本..."
  sleep 1
done
