# =============================================================
# ApeAdmin-Spring Dockerfile
#
# 构建： docker build -t apeadmin-spring:latest .
# 运行： docker run -d --name apeadmin \
#           -p 18080:18080 \
#           -v apeadmin-data:/app/data \
#           -e APE_JWT_SECRET=<生产请更换> \
#           -e APE_APP_VERSION=1.0.0 \
#           --restart unless-stopped \
#           apeadmin-spring:latest
# 访问： http://127.0.0.1:18080/admin/  （默认 admin / ApeAdmin@2026）
#
# 说明：
#   - 运行态（SQLite 库 / 上传 / 插件）统一落 /app/data，挂卷即可持久化
#   - 一键更新约定退出码 90 的自动拉起交给 --restart unless-stopped
# =============================================================
FROM eclipse-temurin:21-jre

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-Xms256m -Xmx1024m"

WORKDIR /app

COPY apeadmin.jar /app/apeadmin.jar

RUN mkdir -p /app/data

CMD ["sh", "-c", "exec java $JAVA_OPTS \
  -Dape.app.version=${APE_APP_VERSION:-1.0.0} \
  -Dspring.datasource.url='jdbc:sqlite:/app/data/apeadmin_spring.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=on' \
  -Dape.upload-dir=/app/data/uploads \
  -Dape.plugin.dir=/app/data/plugins \
  -jar /app/apeadmin.jar"]

EXPOSE 18080
