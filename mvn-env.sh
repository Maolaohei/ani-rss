#!/bin/bash
#
# mvn-env.sh — 在 Windows git-bash (MSYS/MinGW) 下修复 Maven 构建环境
#
# 解决的问题:
#   1. JAVA_HOME 未设置时 mvn 无法启动
#   2. git-bash 的 PATH 混入 Windows 反斜杠条目时, mvn 脚本的
#      mingw 分支会解析出错 (ClassNotFoundException: ...Launcher)
#
# 用法:
#   ./mvn-env.sh <mvn 参数...>           # 任意 mvn 命令, 参数原样透传
#
# 示例:
#   ./mvn-env.sh -version
#   ./mvn-env.sh compile -o -pl ani-rss-application -Dskip.pnpm -Dskip.installnodenpm
#   ./mvn-env.sh package -DskipTests -P windows,macos
#
# 环境变量:
#   JAVA_HOME 已设置且有效时直接使用, 否则自动探测常见 JDK 安装位置

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------- 1. 探测 JDK ----------

find_jdk() {
    local candidate
    # 常见 JDK 安装位置 (取版本号最大者)
    local roots=(
        "/c/Program Files/Java"
        "/c/Program Files/Oracle/Java"
        "/c/Program Files/Eclipse Adoptium"
        "/c/Program Files/Microsoft"
        "/c/Users/$USERNAME/scoop/apps"
        "/c/Users/$USERNAME/.jdks"
    )
    local found=""
    for root in "${roots[@]}"; do
        [ -d "$root" ] || continue
        # 直接子目录含 bin/java.exe
        while IFS= read -r candidate; do
            if [ -x "$candidate/bin/java.exe" ]; then
                if [ -z "$found" ] || [ "$candidate" \> "$found" ]; then
                    found="$candidate"
                fi
            fi
        done < <(find "$root" -maxdepth 3 -type d \( -iname "*jdk*" -o -iname "*temurin*" -o -iname "*openjdk*" -o -iname "*corretto*" -o -iname "*zulu*" \) 2>/dev/null)
    done
    if [ -n "$found" ]; then
        echo "$found"
        return 0
    fi
    return 1
}

resolve_jdk() {
    # 1) JAVA_HOME 已设置且有效
    if [ -n "${JAVA_HOME:-}" ]; then
        local jh
        # 兼容反斜杠/正斜杠路径
        jh="$(cygpath -u "$JAVA_HOME" 2>/dev/null || echo "$JAVA_HOME")"
        if [ -x "$jh/bin/java.exe" ]; then
            echo "$jh"
            return 0
        fi
        echo "[mvn-env] 警告: JAVA_HOME='$JAVA_HOME' 无效, 自动探测替代 JDK" >&2
    fi
    # 2) 自动探测
    local jdk
    if jdk="$(find_jdk)"; then
        echo "[mvn-env] 自动探测到 JDK: $jdk" >&2
        echo "$jdk"
        return 0
    fi
    # 3) PATH 中的 java
    local jcmd
    if jcmd="$(command -v java 2>/dev/null)"; then
        local real_jdk
        real_jdk="$(cd "$(dirname "$(readlink -f "$jcmd" 2>/dev/null || echo "$jcmd")")/.." 2>/dev/null && pwd)"
        if [ -x "$real_jdk/bin/java.exe" ]; then
            echo "$real_jdk"
            return 0
        fi
    fi
    echo "[mvn-env] 错误: 未找到 JDK, 请安装 JDK 或设置 JAVA_HOME" >&2
    return 1
}

# ---------- 2. 探测 Maven ----------

find_maven() {
    local mvn_cmd
    if mvn_cmd="$(command -v mvn 2>/dev/null)"; then
        # mvn 可能是 symlink/shim, 解析到真实脚本
        local real
        real="$(readlink -f "$mvn_cmd" 2>/dev/null || echo "$mvn_cmd")"
        local home
        home="$(cd "$(dirname "$real")/.." && pwd)"
        if [ -d "$home/boot" ] && [ -x "$home/bin/mvn" ]; then
            echo "$home"
            return 0
        fi
    fi
    echo "[mvn-env] 错误: 未找到 Maven (mvn), 请安装后重试" >&2
    return 1
}

# ---------- 3. 组装干净环境并执行 ----------

main() {
    local jdk_home maven_home
    jdk_home="$(resolve_jdk)" || return 1
    maven_home="$(find_maven)" || return 1

    # 干净 PATH: JDK + Maven + MSYS 基础工具链 (+ node/pnpm 若存在, 供前端构建)
    local clean_path="$jdk_home/bin:$maven_home/bin:/usr/bin:/bin:/mingw64/bin"
    for extra in "/c/Program Files/nodejs" "$(dirname "$(command -v node 2>/dev/null)")" ; do
        [ -n "$extra" ] && [ -d "$extra" ] && clean_path="$clean_path:$extra"
    done

    # 环境变量: 只传必要的, 避免 PATH 污染; 保留常用代理/本地仓库配置
    local -a envs=()
    envs+=(PATH="$clean_path")
    envs+=(JAVA_HOME="$(cygpath -w "$jdk_home")")
    envs+=(MAVEN_HOME="$maven_home")
    envs+=(HOME="$HOME")
    # 透传可能影响构建的用户变量
    for v in MAVEN_OPTS MAVEN_ARGS MAVEN_CONFIG HTTP_PROXY HTTPS_PROXY NO_PROXY http_proxy https_proxy no_proxy; do
        if [ -n "${!v:-}" ]; then
            envs+=("$v=${!v}")
        fi
    done
    # 透传临时目录（缺失时 java.io.tmpdir 会落到 C:\WINDOWS 导致测试写入失败）
    for v in TEMP TMP; do
        if [ -n "${!v:-}" ]; then
            envs+=("$v=${!v}")
        fi
    done

    echo "[mvn-env] JAVA_HOME=$(cygpath -w "$jdk_home")" >&2
    echo "[mvn-env] Maven    =$maven_home" >&2
    echo "[mvn-env] mvn $*" >&2

    env -i "${envs[@]}" mvn "$@"
}

main "$@"
