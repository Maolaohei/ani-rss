#!/bin/sh
# 构建 jlink 精简镜像（A + B + C 三件套的入口）。
#
# 用法：
#   docker/build-image.sh                 # amd64 + musl（默认，最小）
#   docker/build-image.sh arm64           # arm64 + musl
#   LIBC=glibc docker/build-image.sh      # glibc 变体（磁力可用，大 ~55 MB）
#
# 环境变量：
#   LIBC=musl|glibc   libc 变体，默认 musl
#   SKIP_MAVEN=1      复用已有 ani-rss-application/target/ani-rss.jar（不重跑 mvn）
#   IMAGE=repo/name   镜像名，默认 ani-rss
#   TAG=xxx           标签，默认 <version>-<arch>-<libc>
#   PUSH=1            直接推送
#
# 做的事：
#   1) mvn package -Dnative.platform=...  → 只带目标平台的 jlibtorrent（省 ~13 MB）
#   2) tools/slim_fat_jar.py              → sqlite 原生库只留一个并抽到 jar 外（省 ~10.6 MB）
#   3) docker buildx build -f Dockerfile-jlink → 用 jlink 裁出的运行时（省 ~83 MB）
#
# 注意：**发布用的通用 jar 不要用这里的参数**，package.sh / build.yml 保持不带
# -Dnative.platform，产出的 jar 才同时支持 Windows / Linux / macOS。

set -eu

ARCH="${1:-amd64}"
LIBC="${LIBC:-musl}"
IMAGE="${IMAGE:-ani-rss}"
SKIP_MAVEN="${SKIP_MAVEN:-0}"
PUSH="${PUSH:-0}"

cd "$(dirname "$0")/.."

case "$ARCH" in
  amd64) JAR_ARCH="x86_64" ;;
  arm64) JAR_ARCH="aarch64" ;;
  *) echo "不支持的架构: $ARCH（可用 amd64 / arm64）" >&2; exit 2 ;;
esac

if [ "$LIBC" = "musl" ]; then
  # jlibtorrent 没有 musl 构建：镜像里干脆不带平台包，磁力降级为可读提示
  MAVEN_PLATFORM="none"
  SQLITE_PLATFORM="linux-musl-$([ "$JAR_ARCH" = "x86_64" ] && echo x86_64 || echo arm64)"
  BUILDER_IMAGE="eclipse-temurin:21-jdk-alpine"
  RUNTIME_IMAGE="alpine:3.21"
else
  MAVEN_PLATFORM="linux-$([ "$JAR_ARCH" = "x86_64" ] && echo x86_64 || echo arm64)"
  SQLITE_PLATFORM="$MAVEN_PLATFORM"
  BUILDER_IMAGE="eclipse-temurin:21-jdk"
  RUNTIME_IMAGE="debian:bookworm-slim"
fi

VERSION=$(mvn -B -q help:evaluate -Dexpression=project.version -DforceStdout -f pom.xml 2>/dev/null || echo dev)
TAG="${TAG:-$VERSION-$ARCH-$LIBC}"

echo "==> arch=$ARCH libc=$LIBC maven.platform=$MAVEN_PLATFORM sqlite.platform=$SQLITE_PLATFORM"
echo "==> image=$IMAGE:$TAG"

if [ "$SKIP_MAVEN" != "1" ]; then
  echo "==> mvn package（jlibtorrent 只带 $MAVEN_PLATFORM）"
  mvn -B -q package -DskipTests -Dnative.platform="$MAVEN_PLATFORM" --file pom.xml
fi

JAR="ani-rss-application/target/ani-rss.jar"
[ -f "$JAR" ] || { echo "找不到 $JAR，先跑一次 mvn package" >&2; exit 1; }

echo "==> 精简原生库（单架构）"
rm -rf ani-rss-application/target/native ani-rss-application/target/ani-rss-slim.jar
python tools/slim_fat_jar.py \
  --jar "$JAR" \
  --platform "$SQLITE_PLATFORM" \
  --out ani-rss-application/target/ani-rss-slim.jar \
  --native-out ani-rss-application/target/native

echo "==> docker buildx build"
BUILD_ARGS="--build-arg BUILDER_IMAGE=$BUILDER_IMAGE --build-arg RUNTIME_IMAGE=$RUNTIME_IMAGE"
if [ "$PUSH" = "1" ]; then
  docker buildx build \
    --platform "linux/$ARCH" \
    -f docker/Dockerfile-jlink \
    $BUILD_ARGS \
    -t "$IMAGE:$TAG" \
    --push .
else
  docker buildx build \
    --platform "linux/$ARCH" \
    -f docker/Dockerfile-jlink \
    $BUILD_ARGS \
    -t "$IMAGE:$TAG" \
    --load .
fi

echo "==> 完成：$IMAGE:$TAG"
docker images "$IMAGE:$TAG"
