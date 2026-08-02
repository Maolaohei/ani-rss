# 统一使用 Temurin 21 LTS（兼容 Java 17 构建目标；UseCompactObjectHeaders 由 IgnoreUnrecognizedVMOptions 兜底忽略）
FROM eclipse-temurin:21-jre-alpine

COPY docker/run.sh /run.sh
COPY docker/exec.sh /exec.sh
COPY ani-rss-application/target/ani-rss.jar /usr/app/ani-rss.jar
WORKDIR /usr/app
VOLUME /config
ENV PUID=0 PGID=0 UMASK=022
ENV JAVA_OPTS="-Xms64m -Xmx512m -Xss256k -XX:+UseG1GC"
ENV SERVER_PORT=7789 CONFIG=/config TZ=Asia/Shanghai SWAGGER_ENABLED=false MCP_ENABLED=false SERVER_ADDRESS=0.0.0.0
EXPOSE $SERVER_PORT
RUN sed -i 's/\r//g' /*.sh && chmod +x /*.sh
CMD ["/exec.sh"]
