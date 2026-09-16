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

if [ -z "$JAVA_OPTS" ]; then
  export JAVA_OPTS="-Xms64m -Xmx512m -Xss512k -XX:+UseG1GC"
fi

# OOM 现场：转储目录必须先存在，否则 JVM 会放弃转储、只留一行警告
: "${CONFIG:=.}"
mkdir -p "$CONFIG/logs"

echo "JAVA_OPTS=$JAVA_OPTS"

while :
do
    # 注意：不要加 -XX:TieredStopAtLevel=1。那是"加快启动"的取舍，代价是 C2 永不启用；
    # 本项目是常驻服务，热点在 JSON 序列化/正则/字符串处理上，C1-only 会让吞吐下降数倍。
    # IgnoreUnrecognizedVMOptions 必须位于 UseCompactObjectHeaders(JDK24+) 之前，否则 JDK 17~23 启动失败
    java $JAVA_OPTS \
      -XX:+UseStringDeduplication \
      -XX:+HeapDumpOnOutOfMemoryError \
      -XX:HeapDumpPath="$CONFIG/logs" \
      -XX:+ExitOnOutOfMemoryError \
      -XX:MaxMetaspaceSize=256m \
      -XX:+IgnoreUnrecognizedVMOptions \
      -XX:+UseCompactObjectHeaders \
      --enable-native-access=ALL-UNNAMED \
      --add-opens=java.base/java.net=ALL-UNNAMED \
      --add-opens=java.base/sun.net.www.protocol.https=ALL-UNNAMED \
      -Dfile.encoding=UTF-8 \
      -jar $JAR_FILE&
    wait $!
    if [ $? -ne 0 ]; then
      break
    fi
done

exit 0
