#!/bin/bash
# ANI-RSS 服务控制脚本
# 用法: ani-rss [start|stop|restart|status|log|switch|uninstall|help]

# 定义颜色代码
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

SERVICE="ani-rss.service"
SERVICE_FILE="/etc/systemd/system/${SERVICE}"
INSTALL_DIR="/opt/ani-rss"
REPO_FILE="$INSTALL_DIR/repo.conf"

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

# 显示帮助信息
show_help() {
    echo -e "${YELLOW}使用方法:"
    echo "  ani-rss start     启动服务"
    echo "  ani-rss stop      停止服务"
    echo "  ani-rss restart   重启服务"
    echo "  ani-rss status    查看服务状态"
    echo "  ani-rss log       查看服务日志"
    echo "  ani-rss switch    切换版本(分支版/官方版)"
    echo "  ani-rss uninstall 卸载"
    echo -e "  ani-rss help      显示帮助信息${NC}"
}

# 切换版本
switch_version() {
    echo -e "${YELLOW}当前仓库源:$(cat "$REPO_FILE" 2>/dev/null || echo "未知")${NC}"
    echo ""
    echo -e "${YELLOW}请选择要切换到的版本:${NC}"
    echo "  1) 分支版 (Maolaohei/ani-rss)"
    echo "  2) 官方版 (wushuo894/ani-rss)"
    read -p "请选择 [1/2]: " repo_choice

    case "$repo_choice" in
        1) GITHUB_REPO="$REPO_FORK" ;;
        2) GITHUB_REPO="$REPO_OFFICIAL" ;;
        *) echo -e "${RED}无效选择${NC}" && exit 1 ;;
    esac

    echo -e "${YELLOW}正在停止服务...${NC}"
    systemctl stop "$SERVICE" 2>/dev/null

    echo "正在下载 ani-rss.jar"
    if ! wget -q "https://github.com/${GITHUB_REPO}/releases/latest/download/ani-rss.jar" -O "$INSTALL_DIR/ani-rss.jar"; then
        echo -e "${RED}下载 ani-rss.jar 失败${NC}"
        exit 1
    fi

    echo "正在下载 run.sh"
    if ! wget -q "https://github.com/${GITHUB_REPO}/raw/master/docker/run.sh" -O "$INSTALL_DIR/run.sh"; then
        echo -e "${RED}下载 run.sh 失败${NC}"
        exit 1
    fi

    echo "$GITHUB_REPO" > "$REPO_FILE"
    chown -R ani-rss:ani-rss "$INSTALL_DIR"
    chmod 770 "$INSTALL_DIR/run.sh"

    echo -e "${YELLOW}正在启动服务...${NC}"
    systemctl start "$SERVICE"

    echo -e "${GREEN}已切换到: ${GITHUB_REPO}${NC}"
}

# 操作执行函数
service_action() {
    case $1 in
        start)
            echo -e "${YELLOW}正在启动服务...${NC}"
            systemctl start "$SERVICE" && echo -e "${GREEN}服务启动成功${NC}" || {
                echo -e "${RED}启动失败，当前状态：${NC}"
                systemctl status "$SERVICE" --no-pager
                exit 1
            }
            ;;
        stop)
            echo -e "${YELLOW}正在停止服务...${NC}"
            systemctl stop "$SERVICE" && echo -e "${GREEN}服务停止成功${NC}" || {
                echo -e "${RED}停止失败，当前状态：${NC}"
                systemctl status "$SERVICE" --no-pager
                exit 1
            }
            ;;
        restart)
            echo -e "${YELLOW}正在重启服务...${NC}"
            systemctl restart "$SERVICE" && echo -e "${GREEN}服务重启成功${NC}" || {
                echo -e "${RED}重启失败，当前状态：${NC}"
                systemctl status "$SERVICE" --no-pager
                exit 1
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
        uninstall)
            /bin/bash -c "$(curl -fsSL https://github.com/Maolaohei/ani-rss/raw/master/linux/uninstall-ani-rss.sh)"
            ;;
        *)
            show_help
            exit 1
            ;;
    esac
}

# 主程序
main() {
    check_root
    check_service

    if [ $# -eq 0 ]; then
        show_help
        exit 1
    fi

    case $1 in
        help) show_help ;;
        *) service_action "$1" ;;
    esac
}

main "$@"