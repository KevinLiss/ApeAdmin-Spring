#!/bin/sh
# =============================================================
# ApeAdmin-Spring 发布包构建脚本
#
# 用途：mvn 打包 fat jar → 组装发布目录 → 生成 zip + SHA256
# 产物结构（与 /system/update 契约一致：zip 根含一个顶层目录，
#           内为 apeadmin.jar；版本号注入 start 脚本）：
#   apeadmin-spring-{version}/
#     ├─ apeadmin.jar
#     ├─ apeadmin-start.sh      （*nix 守护启动，支持自更新退出码 90 拉起）
#     ├─ apeadmin-start.bat     （Windows 守护启动）
#     └─ README.txt             （运行说明）
#
# 用法：./release/package-release.sh [版本号]
#   缺省版本号 = 1.0.0（正式发布请显式传入，如 1.1.0）
#
# 说明：本脚本只打包构建产物，天然不含任何运行态残留
#       （setup.lock / .env / *.db / keys / .hermes_token / uploads / plugins 均不进入包）。
# =============================================================
set -eu

VERSION="${1:-1.0.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/release/dist"
STAGE="$DIST/apeadmin-spring-$VERSION"

echo "==> [1/4] Maven 打包（-DskipTests）"
# mvn 路径自适应：优先 PATH，回退到本机常用安装位置
MVN="$(command -v mvn || true)"
[ -z "$MVN" ] && [ -x "$HOME/tools/apache-maven-3.9.16/bin/mvn" ] && MVN="$HOME/tools/apache-maven-3.9.16/bin/mvn"
[ -z "$MVN" ] && { echo "错误：未找到 mvn，请先安装 Maven 或加入 PATH"; exit 1; }
(cd "$ROOT" && JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk-21.0.13.1+2/Contents/Home}" "$MVN" -q package -DskipTests)
JAR="$ROOT/ape-server/target/apeadmin.jar"
[ -f "$JAR" ] || { echo "错误：未找到 $JAR"; exit 1; }

echo "==> [2/4] 组装发布目录 $STAGE"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$JAR" "$STAGE/apeadmin.jar"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cp "$SCRIPT_DIR/apeadmin-start.sh"  "$STAGE/apeadmin-start.sh"
cp "$SCRIPT_DIR/apeadmin-start.bat" "$STAGE/apeadmin-start.bat"
chmod +x "$STAGE/apeadmin-start.sh"

cat > "$STAGE/README.txt" <<EOF
ApeAdmin-Spring v$VERSION
========================================

运行（*nix）：
  sh apeadmin-start.sh
  浏览器访问 http://127.0.0.1:18080/admin/

运行（Windows）：
  双击 apeadmin-start.bat

默认账号：admin / ApeAdmin@2026（首次登录后请立即修改）

数据与运行时文件（自动生成于运行目录）：
  apeadmin_spring.db  SQLite 数据库（默认 profile）
  uploads/            上传文件
  plugins/            插件目录
  keys/               密钥目录（缺失自动生成）
  .hermes_token       内部令牌（缺失自动生成）

升级：管理后台「系统设置 → 版本信息」一键更新（约定退出码 90 由
守护脚本自动拉起新版本）；或直接替换 apeadmin.jar 后重启。
EOF

echo "==> [3/4] 注入版本号"
sed -e "s/@VERSION@/$VERSION/g" "$SCRIPT_DIR/apeadmin-start.sh"  > "$STAGE/apeadmin-start.sh.tmp"  && mv "$STAGE/apeadmin-start.sh.tmp" "$STAGE/apeadmin-start.sh" && chmod +x "$STAGE/apeadmin-start.sh"
sed -e "s/@VERSION@/$VERSION/g" "$SCRIPT_DIR/apeadmin-start.bat" > "$STAGE/apeadmin-start.bat.tmp" && mv "$STAGE/apeadmin-start.bat.tmp" "$STAGE/apeadmin-start.bat"

echo "==> [4/4] 打包 zip + SHA256"
ZIP="$DIST/apeadmin-spring-$VERSION.zip"
rm -f "$ZIP"
(cd "$DIST" && zip -q -r -X "$ZIP" "apeadmin-spring-$VERSION")
shasum -a 256 "$ZIP" > "$ZIP.sha256"
echo "完成："
echo "  $ZIP"
echo "  $ZIP.sha256"
