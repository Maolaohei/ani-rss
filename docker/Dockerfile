# 统一使用 Temurin 21 LTS（兼容 Java 17 构建目标；UseCompactObjectHeaders 由 IgnoreUnrecognizedVMOptions 兜底忽略）

# ---- 安全：从信任库中移除 eMudhra（emSign）根证书 ----
# 背景：2026-10 披露的 eMudhra 中介 CA 私钥外泄事件（CVE-2026-91403）。Temurin 21.0.12
# 及更早版本的 cacerts 仍信任 emSign Root CA G1/G2 与 ECC Root CA G3，任何持有泄漏私钥者
# 均可签发被本镜像接受的服务端证书，从而中间人劫持 115 / mikan / bgm.tv 等出站 HTTPS。
# 这里用 JDK 阶段的 keytool 删除这些根证书，再把干净的 cacerts 复制进运行时镜像。
# 上游 JDK（21.0.13 / 17.0.21 / 25.0.5）正式发布并移除该 CA 后，可删除本阶段与下方 COPY。
FROM eclipse-temurin:21-jdk-alpine AS cacerts
RUN set -eux; \
    keytool -J-Duser.language=en -list -v -cacerts -storepass changeit \
      | sed -n 's/^Alias name: //p' | grep -i emsign > /tmp/emsign || true; \
    while IFS= read -r a; do \
      keytool -J-Duser.language=en -delete -cacerts -storepass changeit -alias "$a"; \
    done < /tmp/emsign; \
    if keytool -J-Duser.language=en -list -cacerts -storepass changeit | grep -qi emsign; then \
      echo "emSign roots still present in cacerts" >&2; exit 1; \
    fi; \
    echo "emSign roots removed from cacerts"

FROM eclipse-temurin:21-jre-alpine
COPY --from=cacerts /opt/java/openjdk/lib/security/cacerts /opt/java/openjdk/lib/security/cacerts

COPY docker/run.sh /run.sh
COPY docker/exec.sh /exec.sh
COPY ani-rss-application/target/ani-rss.jar /usr/app/ani-rss.jar
WORKDIR /usr/app
VOLUME /config
ENV PUID=0 PGID=0 UMASK=022
ENV JAVA_OPTS="-Xms64m -Xmx512m -Xss512k -XX:+UseG1GC"
ENV SERVER_PORT=7789 CONFIG=/config TZ=Asia/Shanghai SWAGGER_ENABLED=false MCP_ENABLED=false SERVER_ADDRESS=0.0.0.0
EXPOSE $SERVER_PORT
RUN sed -i 's/\r//g' /*.sh && chmod +x /*.sh
CMD ["/exec.sh"]
