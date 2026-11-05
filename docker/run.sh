#!/bin/sh

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

FOLDER="./"
JAR_FILE_NAME="ani-rss.jar"
JAR_FILE=$FOLDER$JAR_FILE_NAME

if [ ! -f $JAR_FILE ]; then
    URL="https://github.com/Maolaohei/ani-rss/releases/latest/download/ani-rss.jar"
    wget -O $JAR_FILE $URL

    if [ $? -eq 0 ]; then
        echo "$JAR_FILE 下载成功！"
    else
        echo "$JAR_FILE 下载失败。"
    fi
fi

stop() {
  PID=$(pgrep -f "$JAR_FILE_NAME")
  if [ -n "$PID" ]; then
      echo "Stopping process $PID - $JAR_FILE_NAME"
      kill "$PID"
      wait "$PID"
  fi
}

stop

sigterm_handler() {
    stop
}

trap 'sigterm_handler' 15

# ─────────────────────────────────────────────────────────────────────────────
# JVM 预设档（C 方案）
#
# 实测依据（JDK 21，空配置启动，见 docs/jvm-optimization-plan.md）：
#   G1 + Xmx512     → 启动 6.50s，常驻 WS 225.9MB，Private 289.4MB，线程 60
#   SerialGC+Xmx256 → 启动 5.55s，常驻 WS 206.1MB，Private 221.9MB，线程 37
# 内存的大头不是堆，而是 G1 的辅助结构（NMT: committed 257MB 里 GC 结构占 66MB）+
# 元空间 57MB + 代码缓存 25MB。所以 lowmem 档的关键是换 SerialGC，而不是砍 -Xmx。
#
# 预设只决定"默认值"：显式传 JAVA_OPTS 时一切以 JAVA_OPTS 为准（老用户的启动参数不受影响）
# ─────────────────────────────────────────────────────────────────────────────
: "${JAVA_BIN:=java}"
: "${JAVA_PROFILE:=default}"

case "$JAVA_PROFILE" in
  lowmem)
    PRESET_OPTS="-Xms32m -Xmx256m -Xss512k -XX:+UseSerialGC"
    PRESET_METASPACE="${JAVA_METASPACE:-192m}"
    : "${TOMCAT_MAX_THREADS:=40}"
    ;;
  perf)
    PRESET_OPTS="-Xms256m -Xmx1g -Xss512k -XX:+UseG1GC"
    PRESET_METASPACE="${JAVA_METASPACE:-384m}"
    : "${TOMCAT_MAX_THREADS:=200}"
    ;;
  default|*)
    PRESET_OPTS="-Xms64m -Xmx512m -Xss512k -XX:+UseG1GC"
    PRESET_METASPACE="${JAVA_METASPACE:-256m}"
    : "${TOMCAT_MAX_THREADS:=100}"
    ;;
esac
export TOMCAT_MAX_THREADS

if [ -z "$JAVA_OPTS" ]; then
  export JAVA_OPTS="$PRESET_OPTS"
fi

# SerialGC 下 UseStringDeduplication 是空操作（只会在启动日志里多一条警告），跟着 GC 走
case "$JAVA_OPTS" in
  *UseSerialGC*) DEDUP_OPTS="" ;;
  *) DEDUP_OPTS="-XX:+UseStringDeduplication" ;;
esac

# sqlite 原生库外挂：镜像里的 jar 已被 tools/slim_fat_jar.py 裁到只剩目标平台那一个原生库，
# 并把它抽到 jar 外（省 ~10MB）。走 xerial 的 org.sqlite.lib.path / org.sqlite.lib.name，
# 命中时不会再把 .so 解压到临时目录。
if [ -n "$SQLITE_LIB_PATH" ] && [ -n "$SQLITE_LIB_NAME" ] && [ -f "$SQLITE_LIB_PATH/$SQLITE_LIB_NAME" ]; then
  JAVA_OPTS="$JAVA_OPTS -Dorg.sqlite.lib.path=$SQLITE_LIB_PATH -Dorg.sqlite.lib.name=$SQLITE_LIB_NAME"
fi

# OOM 现场：转储目录必须先存在，否则 JVM 会放弃转储、只留一行警告
: "${CONFIG:=.}"
mkdir -p "$CONFIG/logs"

echo "JAVA_PROFILE=$JAVA_PROFILE"
echo "JAVA_BIN=$JAVA_BIN"
echo "JAVA_OPTS=$JAVA_OPTS"
echo "TOMCAT_MAX_THREADS=$TOMCAT_MAX_THREADS"

while :
do
    # 注意：不要加 -XX:TieredStopAtLevel=1。那是"加快启动"的取舍，代价是 C2 永不启用；
    # 本项目是常驻服务，热点在 JSON 序列化/正则/字符串处理上，C1-only 会让吞吐下降数倍。
    # IgnoreUnrecognizedVMOptions 必须位于 UseCompactObjectHeaders(JDK24+) 之前，否则 JDK 17~23 启动失败
    $JAVA_BIN $JAVA_OPTS \\
      $DEDUP_OPTS \\
      -XX:+HeapDumpOnOutOfMemoryError \\
      -XX:HeapDumpPath="$CONFIG/logs" \\
      -XX:+ExitOnOutOfMemoryError \\
      -XX:MaxMetaspaceSize="$PRESET_METASPACE" \\
      -XX:+IgnoreUnrecognizedVMOptions \\
      -XX:+UseCompactObjectHeaders \\
      --enable-native-access=ALL-UNNAMED \\
      --add-opens=java.base/java.net=ALL-UNNAMED \\
      --add-opens=java.base/sun.net.www.protocol.https=ALL-UNNAMED \\
      -Dfile.encoding=UTF-8 \\
      -jar $JAR_FILE&
    wait $!
    if [ $? -ne 0 ]; then
      break
    fi
done

exit 0
