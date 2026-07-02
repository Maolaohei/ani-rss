#!/bin/bash
# ANI-RSS 服务控制脚本
# 用法: ani-rss [菜单交互] 或 ani-rss [start|stop|restart|status|log|switch|network|uninstall|help]

# 定义颜色代码
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

SERVICE="ani-rss.service"
SERVICE_FILE="/etc/systemd/system/${SERVICE}"
INSTALL_DIR="/opt/ani-rss"
REPO_FILE="$INSTALL_DIR/repo.conf"
NETWORK_CONF="$INSTALL_DIR/network.conf"
RUN_MODE_FILE="$INSTALL_DIR/run.mode"

# 仓库源定义
REPO_OFFICIAL="wushuo894/ani-rss"
REPO_FORK="Maolaohei/ani-rss"

# 检查 root 权限
check_root() {
    [ "$EUID" -ne 0 ] && echo -e "${RED}错误：请使用 sudo 或 root 用户执行${NC}" && exit 1
}

# 检查服务是否存在
check_service() {
    [ ! -f "$SERVICE_FILE" ] && echo -e "${RED}错误：服务未安装${NC}" && exit 1
}

# 获取服务状态
get_status() {
    systemctl is-active "$SERVICE" 2>/dev/null || echo "未运行"
}

# 显示主菜单
show_menu() {
    local status=$(get_status)
    local status_color="$RED"
    [ "$status" = "active" ] && status_color="$GREEN"

    echo ""
    echo -e "${CYAN}${BOLD}  ╔═══════════════════════════════════════╗${NC}"
    echo -e "${CYAN}${BOLD}  ║        ANI-RSS 管理面板               ║${NC}"
    echo -e "${CYAN}${BOLD}  ╚═══════════════════════════════════════╝${NC}"
    echo ""
    echo -e "  服务状态: ${status_color}${BOLD}${status}${NC}"
    echo ""
    echo -e "  ${CYAN}┌─────────────────────────────────────┐${NC}"
    echo -e "  ${CYAN}│${NC}  ${BOLD}服务管理${NC}                          ${CYAN}│${NC}"
    echo -e "  ${CYAN}├─────────────────────────────────────┤${NC}"
    echo -e "  ${CYAN}│${NC}  ${GREEN}1)${NC} 启动服务                        ${CYAN}│${NC}"
    echo -e "  ${CYAN}│${NC}  ${RED}2)${NC} 停止服务                        ${CYAN}│${NC}"
    echo -e "  ${CYAN}│${NC}  ${YELLOW}3)${NC} 重启服务                        ${CYAN}│${NC}"
    echo -e "  ${CYAN}│${NC}  ${DIM}4)${NC} 查看状态                        ${CYAN}│${NC}"
    echo -e "  ${CYAN}│${NC}  ${DIM}5)${NC} 查看日志                        ${CYAN}│${NC}"
    echo -e "  ${CYAN}├─────────────────────────────────────┤${NC}"
    echo -e "  ${CYAN}│${NC}  ${BOLD}配置管理${NC}                          ${CYAN}│${NC}"
    echo -e "  ${CYAN}├─────────────────────────────────────┤${NC}"
    echo -e "  ${CYAN}│${NC}  ${CYAN}6)${NC} 切换版本 (分支版/官方版)          ${CYAN}│${NC}"
    echo -e "  ${CYAN}│${NC}  ${CYAN}7)${NC} 切换网络协议 (IPv4/IPv6)         ${CYAN}│${NC}"
    echo -e "  ${CYAN}├─────────────────────────────────────┤${NC}"
    echo -e "  ${CYAN}│${NC}  ${BOLD}其他${NC}                              ${CYAN}│${NC}"
    echo -e "  ${CYAN}├─────────────────────────────────────┤${NC}"
    echo -e "  ${CYAN}│${NC}  ${DIM}8)${NC} 卸载                            ${CYAN}│${NC}"
    echo -e "  ${CYAN}│${NC}  ${DIM}0)${NC} 退出                            ${CYAN}│${NC}"
    echo -e "  ${CYAN}└─────────────────────────────────────┘${NC}"
    echo ""
}

# 菜单循环
menu_loop() {
    while true; do
        show_menu
        read -p "  请选择操作 [0-8]: " choice
        echo ""
        case "$choice" in
            1) service_action start ;;
            2) service_action stop ;;
            3) service_action restart ;;
            4) service_action status ;;
            5) service_action log ;;
            6) switch_version ;;
            7) switch_network ;;
            8) service_action uninstall ;;
            0|q|Q) echo -e "${GREEN}再见！${NC}"; exit 0 ;;
            *) echo -e "${RED}无效选择${NC}" ;;
        esac
        echo ""
        read -p "  按回车返回菜单..." _
    done
}

# 显示命令行帮助
show_help() {
    echo -e "${YELLOW}用法: ani-rss [命令]"
    echo ""
    echo "  直接运行 ani-rss    打开管理菜单"
    echo ""
    echo "  命令行模式:"
    echo "    start             启动服务"
    echo "    stop              停止服务"
    echo "    restart           重启服务"
    echo "    status            查看服务状态"
    echo "    log               查看服务日志"
    echo "    switch            切换版本(分支版/官方版)"
    echo "    network           切换IPv4/IPv6优先级"
    echo "    uninstall         卸载"
    echo "    help              显示帮助信息${NC}"
}

# 切换版本
switch_version() {
    echo -e "${YELLOW}当前仓库源:${NC} $(cat "$REPO_FILE" 2>/dev/null || echo "未知")"
    echo ""
    echo -e "${YELLOW}请选择要切换到的版本:${NC}"
    echo -e "  ${CYAN}1)${NC} 分支版 (Maolaohei/ani-rss)"
    echo -e "  ${CYAN}2)${NC} 官方版 (wushuo894/ani-rss)"
    echo ""
    read -p "请选择 [1/2]: " repo_choice

    case "$repo_choice" in
        1) GITHUB_REPO="$REPO_FORK" ;;
        2) GITHUB_REPO="$REPO_OFFICIAL" ;;
        *) echo -e "${RED}无效选择${NC}" && return ;;
    esac

    echo -e "${YELLOW}正在停止服务...${NC}"
    systemctl stop "$SERVICE" 2>/dev/null

    echo "正在下载 ani-rss.jar"
    if ! wget -q "https://github.com/${GITHUB_REPO}/releases/latest/download/ani-rss.jar" -O "$INSTALL_DIR/ani-rss.jar"; then
        echo -e "${RED}下载 ani-rss.jar 失败${NC}"
        return
    fi

    echo "正在下载 run.sh"
    if ! wget -q "https://github.com/${GITHUB_REPO}/raw/master/docker/run.sh" -O "$INSTALL_DIR/run.sh"; then
        echo -e "${RED}下载 run.sh 失败${NC}"
        return
    fi

    echo "$GITHUB_REPO" > "$REPO_FILE"

    local run_mode=$(cat "$RUN_MODE_FILE" 2>/dev/null || echo "root")
    if [ "$run_mode" = "user" ]; then
        chown -R ani-rss:ani-rss "$INSTALL_DIR"
    fi
    chmod 755 "$INSTALL_DIR/run.sh"

    echo -e "${YELLOW}正在启动服务...${NC}"
    systemctl start "$SERVICE"

    echo -e "${GREEN}已切换到: ${GITHUB_REPO}${NC}"
}

# 切换网络协议优先级
switch_network() {
    local current_opts=$(cat "$NETWORK_CONF" 2>/dev/null || echo "")

    if [ -n "$current_opts" ]; then
        echo -e "当前网络: ${GREEN}IPv4 优先${NC}"
    else
        echo -e "当前网络: ${GREEN}IPv6 优先${NC}"
    fi

    echo ""
    echo -e "${YELLOW}请选择网络协议:${NC}"
    echo -e "  ${CYAN}1)${NC} IPv4 优先 (推荐海外VPS, 解决连接超时问题)"
    echo -e "  ${CYAN}2)${NC} IPv6 优先 (默认)"
    echo ""
    read -p "请选择 [1/2]: " network_choice

    case "$network_choice" in
        1) new_opts="-Djava.net.preferIPv4Stack=true" ;;
        2) new_opts="" ;;
        *) echo -e "${RED}无效选择${NC}" && return ;;
    esac

    if [ "$current_opts" = "$new_opts" ]; then
        echo -e "${GREEN}设置未变化，无需修改${NC}"
        return
    fi

    echo "$new_opts" > "$NETWORK_CONF"

    local base_opts="-Xms64m -Xmx512m -Xss256k -XX:+UseG1GC"
    local new_java_opts="$base_opts $new_opts"
    sed -i "s|^Environment=\"JAVA_OPTS=.*\"|Environment=\"JAVA_OPTS=$new_java_opts\"|" "$SERVICE_FILE"

    systemctl daemon-reload
    systemctl restart "$SERVICE"

    if [ -z "$new_opts" ]; then
        echo -e "${GREEN}已切换到 IPv6 优先，服务已重启${NC}"
    else
        echo -e "${GREEN}已切换到 IPv4 优先，服务已重启${NC}"
    fi
}

# 操作执行函数
service_action() {
    case $1 in
        start)
            echo -e "${YELLOW}正在启动服务...${NC}"
            systemctl start "$SERVICE" && echo -e "${GREEN}服务启动成功${NC}" || {
                echo -e "${RED}启动失败，当前状态：${NC}"
                systemctl status "$SERVICE" --no-pager
            }
            ;;
        stop)
            echo -e "${YELLOW}正在停止服务...${NC}"
            systemctl stop "$SERVICE" && echo -e "${GREEN}服务停止成功${NC}" || {
                echo -e "${RED}停止失败，当前状态：${NC}"
                systemctl status "$SERVICE" --no-pager
            }
            ;;
        restart)
            echo -e "${YELLOW}正在重启服务...${NC}"
            systemctl restart "$SERVICE" && echo -e "${GREEN}服务重启成功${NC}" || {
                echo -e "${RED}重启失败，当前状态：${NC}"
                systemctl status "$SERVICE" --no-pager
            }
            ;;
        log)
            journalctl -u "$SERVICE" -f
            ;;
        status)
            systemctl status "$SERVICE" --no-pager
            ;;
        switch)
            switch_version
            ;;
        network)
            switch_network
            ;;
        uninstall)
            /bin/bash -c "$(curl -fsSL https://github.com/Maolaohei/ani-rss/raw/master/linux/uninstall-ani-rss.sh)"
            ;;
        *)
            show_help
            ;;
    esac
}

# 主程序
main() {
    check_root
    check_service

    if [ $# -eq 0 ]; then
        # 无参数：进入菜单模式
        menu_loop
    else
        # 有参数：命令行模式
        case $1 in
            help|-h|--help) show_help ;;
            *) service_action "$1" ;;
        esac
    fi
}

main "$@"
