#!/usr/bin/env bash
#
# 一次性升级 ani-rss 登录密码存储格式。
#
# 支持：
#   1. 保留旧密码：校 MD5/旧明文后，改为单层 SHA-256
#   2. 设置新密码：直接写入单层 SHA-256
#
# 脚本会先备份 config.v2.json，再原子替换；检测到 ani-rss.service 时会
# 自动停止并在修改成功后启动。Docker/自定义部署可用 --service none，
# 但必须先把 ani-rss 进程或容器停掉。

set -Eeuo pipefail
umask 077

CONFIG_INPUT="${CONFIG_FILE:-}"
SERVICE_INPUT=""
SERVICE_SPECIFIED=0
MODE=""
ASSUME_YES=0

SERVICE=""
SERVICE_STOPPED=0
TMP_FILE=""

die() {
    printf '错误: %s\n' "$*" >&2
    exit 1
}

info() {
    printf '%s\n' "$*"
}

usage() {
    cat <<'EOF'
用法:
  upgrade-password-hash.sh [选项]

选项:
  --config PATH          config.v2.json 或它所在的配置目录
  --service NAME         systemd 服务名，默认自动检测 ani-rss.service
  --service none         不管理 systemd，需先手动停止 ani-rss
  --keep-password        输入旧明文，验证通过后保留同一密码并升级为 SHA-256
  --set-password         输入两遍新密码并写入 SHA-256
  --yes                  跳过最终确认
  -h, --help             显示帮助

示例:
  bash linux/upgrade-password-hash.sh --config /opt/ani-rss/config
  bash linux/upgrade-password-hash.sh --config /opt/ani-rss/config --set-password
  bash linux/upgrade-password-hash.sh --config /path/to/config --service none
EOF
}

cleanup() {
    local rc=$?
    if [[ -n "${TMP_FILE:-}" && -f "${TMP_FILE}" ]]; then
        rm -f -- "$TMP_FILE"
    fi
    if (( rc != 0 && SERVICE_STOPPED == 1 )); then
        printf '操作未完成，尝试重新启动 %s...\n' "$SERVICE" >&2
        systemctl start "$SERVICE" >/dev/null 2>&1 || true
    fi
}

trap cleanup EXIT

while (( $# > 0 )); do
    case "$1" in
        --config)
            [[ $# -ge 2 ]] || die "--config 缺少路径"
            CONFIG_INPUT="$2"
            shift 2
            ;;
        --service)
            [[ $# -ge 2 ]] || die "--service 缺少服务名"
            SERVICE_INPUT="$2"
            SERVICE_SPECIFIED=1
            shift 2
            ;;
        --keep-password)
            [[ -z "$MODE" || "$MODE" == "keep" ]] || die "密码模式只能选择一个"
            MODE="keep"
            shift
            ;;
        --set-password)
            [[ -z "$MODE" || "$MODE" == "set" ]] || die "密码模式只能选择一个"
            MODE="set"
            shift
            ;;
        --yes|-y)
            ASSUME_YES=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "未知参数: $1"
            ;;
    esac
done

for cmd in jq sha256sum md5sum awk date realpath; do
    command -v "$cmd" >/dev/null 2>&1 || die "缺少命令: $cmd"
done

resolve_config() {
    local candidate=""
    local entry=""
    local -a candidates=()

    if [[ -n "$CONFIG_INPUT" ]]; then
        candidate="$CONFIG_INPUT"
        if [[ -d "$candidate" ]]; then
            candidate="${candidate%/}/config.v2.json"
        fi
    else
        if [[ -n "${CONFIG:-}" ]]; then
            candidates+=("${CONFIG%/}/config.v2.json")
        fi
        candidates+=(
            "/opt/ani-rss/config/config.v2.json"
            "/config/config.v2.json"
            "$PWD/config.v2.json"
        )
        for entry in "${candidates[@]}"; do
            if [[ -f "$entry" ]]; then
                candidate="$entry"
                break
            fi
        done
    fi

    [[ -n "$candidate" && -f "$candidate" ]] || die "找不到 config.v2.json，请用 --config 指定"
    realpath -- "$candidate"
}

resolve_service() {
    local unit=""

    if (( SERVICE_SPECIFIED )); then
        printf '%s' "$SERVICE_INPUT"
        return
    fi

    if command -v systemctl >/dev/null 2>&1; then
        for unit in ani-rss.service ani-rss; do
            if systemctl list-unit-files --no-legend "$unit" 2>/dev/null | grep -q '[^[:space:]]'; then
                printf '%s' "$unit"
                return
            fi
        done
    fi

    printf ''
}

ensure_stopped_without_systemd() {
    if command -v pgrep >/dev/null 2>&1 && pgrep -af 'ani-rss\.jar' >/dev/null 2>&1; then
        die "检测到 ani-rss.jar 仍在运行，请先停止进程后再执行，或改用 --service ani-rss"
    fi

    if command -v docker >/dev/null 2>&1 &&
        docker ps --format '{{.Names}} {{.Image}}' 2>/dev/null |
            grep -Eqi '(^|[[:space:]/])ani-rss([[:space:]:]|$)'; then
        die "检测到 ani-rss 容器仍在运行，请先 docker stop 容器后再执行"
    fi
}

hash_text() {
    local algorithm="$1"
    local text="$2"
    printf '%s' "$text" | "${algorithm}sum" | awk '{print $1}'
}

CONFIG_FILE="$(resolve_config)"

CURRENT_USER="$(jq -er '.login.username | select(type == "string" and length > 0)' "$CONFIG_FILE")" ||
    die "配置中缺少 login.username"
CURRENT_PASSWORD="$(
    jq -er '.login.password | select(type == "string" and length > 0)' "$CONFIG_FILE"
)" || die "配置中缺少 login.password"

PASSWORD_LENGTH="${#CURRENT_PASSWORD}"
CURRENT_PASSWORD_LOWER="$(printf '%s' "$CURRENT_PASSWORD" | tr 'A-F' 'a-f')"

if [[ "$PASSWORD_LENGTH" -eq 32 && "$CURRENT_PASSWORD_LOWER" =~ ^[0-9a-f]{32}$ ]]; then
    CURRENT_FORMAT="旧版 MD5"
elif [[ "$PASSWORD_LENGTH" -eq 64 && "$CURRENT_PASSWORD_LOWER" =~ ^[0-9a-f]{64}$ ]]; then
    CURRENT_FORMAT="SHA-256 或双层 SHA-256"
else
    CURRENT_FORMAT="未知/明文"
fi

info "配置文件: $CONFIG_FILE"
info "登录用户: $CURRENT_USER"
info "当前格式: $CURRENT_FORMAT (${PASSWORD_LENGTH} 位)"

if [[ -z "$MODE" ]]; then
    if [[ "$CURRENT_FORMAT" == "旧版 MD5" ]]; then
        info ""
        info "检测到旧版 MD5，请选择升级方式："
        info "  1) 我还记得原密码，保留原密码"
        info "  2) 忘记原密码，设置新密码"
        read -rp "请选择 [1/2]: " choice
        case "$choice" in
            1) MODE="keep" ;;
            2) MODE="set" ;;
            *) die "无效选择" ;;
        esac
    elif [[ "$CURRENT_FORMAT" == "SHA-256 或双层 SHA-256" ]]; then
        info ""
        info "当前配置已是 SHA-256 形态，无需一次性升级。"
        info "如需修改密码，请加 --set-password 重新运行。"
        exit 0
    else
        die "无法识别当前密码格式，请检查配置或手动重置"
    fi
fi

NEW_USER="$CURRENT_USER"
NEW_PASSWORD=""

if [[ "$MODE" == "keep" ]]; then
    read -rsp "请输入当前明文密码: " OLD_PASSWORD
    printf '\n'
    [[ -n "$OLD_PASSWORD" ]] || die "密码不能为空"

    OLD_MD5="$(hash_text md5 "$OLD_PASSWORD")"
    OLD_SHA256="$(hash_text sha256 "$OLD_PASSWORD")"
    OLD_DOUBLE_SHA256="$(printf '%s' "$OLD_SHA256" | sha256sum | awk '{print $1}')"

    if [[ "$CURRENT_FORMAT" == "旧版 MD5" ]]; then
        [[ "$CURRENT_PASSWORD_LOWER" == "$OLD_MD5" ]] ||
            die "输入的密码与配置中的 MD5 不匹配"
    elif [[ "$CURRENT_FORMAT" == "SHA-256 或双层 SHA-256" ]]; then
        if [[ "$CURRENT_PASSWORD_LOWER" == "$OLD_SHA256" ]]; then
            if [[ "$CURRENT_PASSWORD" == "$OLD_SHA256" ]]; then
                info "当前已经是单层 SHA-256，无需修改"
                unset OLD_PASSWORD OLD_MD5 OLD_SHA256 OLD_DOUBLE_SHA256
                exit 0
            fi
        elif [[ "$CURRENT_PASSWORD_LOWER" != "$OLD_DOUBLE_SHA256" ]]; then
            die "输入的密码与配置中的 SHA-256 不匹配"
        fi
    elif [[ "$CURRENT_PASSWORD" != "$OLD_PASSWORD" ]]; then
        die "输入的密码与配置中的明文不匹配"
    fi

    NEW_PASSWORD="$OLD_PASSWORD"
    unset OLD_PASSWORD OLD_MD5 OLD_SHA256 OLD_DOUBLE_SHA256
else
    while true; do
        read -rsp "请输入新密码: " PASSWORD_ONE
        printf '\n'
        read -rsp "请再次输入新密码: " PASSWORD_TWO
        printf '\n'

        if [[ -z "$PASSWORD_ONE" ]]; then
            info "密码不能为空，请重试"
            continue
        fi
        if [[ "$PASSWORD_ONE" != "$PASSWORD_TWO" ]]; then
            info "两次输入不一致，请重试"
            continue
        fi

        NEW_PASSWORD="$PASSWORD_ONE"
        unset PASSWORD_ONE PASSWORD_TWO
        break
    done
fi

NEW_HASH="$(hash_text sha256 "$NEW_PASSWORD")"
unset NEW_PASSWORD

info ""
info "将写入: $CONFIG_FILE"
info "用户名保持不变: $NEW_USER"
info "新密码格式: 单层 SHA-256"

if (( ! ASSUME_YES )); then
    read -rp "确认升级并重启服务? [y/N]: " confirm
    [[ "$confirm" =~ ^[Yy]$ ]] || die "已取消"
fi

SERVICE="$(resolve_service)"

if [[ -n "$SERVICE" && "$SERVICE" != "none" ]]; then
    if systemctl is-active --quiet "$SERVICE"; then
        info "停止服务: $SERVICE"
        systemctl stop "$SERVICE"
        SERVICE_STOPPED=1
    else
        info "服务当前未运行: $SERVICE"
    fi
else
    ensure_stopped_without_systemd
fi

BACKUP_FILE="${CONFIG_FILE}.bak-$(date +%Y%m%d-%H%M%S)-$$"
cp -a -- "$CONFIG_FILE" "$BACKUP_FILE"
chmod 600 -- "$BACKUP_FILE" 2>/dev/null || true
info "已备份: $BACKUP_FILE"

TMP_FILE="${CONFIG_FILE}.tmp.$$"
cp -a -- "$CONFIG_FILE" "$TMP_FILE"

jq --arg username "$NEW_USER" --arg hash "$NEW_HASH" \
    '.login = ((.login // {}) + {username: $username, password: $hash})' \
    "$CONFIG_FILE" > "$TMP_FILE"

jq -e \
    --arg username "$NEW_USER" \
    --arg hash "$NEW_HASH" \
    '.login.username == $username
     and .login.password == $hash
     and (.login.password | length == 64)
     and (.login.password | test("^[0-9a-f]{64}$"))' \
    "$TMP_FILE" >/dev/null

mv -f -- "$TMP_FILE" "$CONFIG_FILE"
TMP_FILE=""

if (( SERVICE_STOPPED == 1 )); then
    info "启动服务: $SERVICE"
    systemctl start "$SERVICE"
    systemctl is-active --quiet "$SERVICE" ||
        die "服务启动失败，请检查: journalctl -u $SERVICE -n 100 --no-pager"
    SERVICE_STOPPED=0
fi

info ""
info "升级完成"
jq -r '.login.username, (.login.password | length), (.login.password | test("^[0-9a-f]{64}$"))' \
    "$CONFIG_FILE"
info "如需回滚: cp -a '$BACKUP_FILE' '$CONFIG_FILE'"
