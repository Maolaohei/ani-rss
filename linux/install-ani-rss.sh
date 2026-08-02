#!/bin/bash
# ANI-RSS 一体化安装脚本 with Systemd 服务
# 适用系统: Ubuntu/Debian/CentOS/RHEL

# 定义颜色代码
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# 定义常量
INSTALL_DIR="/opt/ani-rss"
SERVICE_USER="root"
SERVICE_NAME="ani-rss.service"
SERVER_PORT="7789"
REPO_FILE="$INSTALL_DIR/repo.conf"
RUN_MODE_FILE="$INSTALL_DIR/run.mode"

# 仓库源定义
REPO_OFFICIAL="wushuo894/ani-rss"
REPO_FORK="Maolaohei/ani-rss"

# 打印 Banner
show_banner() {
    clear
    echo -e "${CYAN}${BOLD}"
    echo "  ╔═══════════════════════════════════════╗"
    echo "  ║        ANI-RSS 安装向导               ║"
    echo "  ╚═══════════════════════════════════════╝"
    echo -e "${NC}"
}

# 检查root权限
check_root() {
    [ "$EUID" -ne 0 ] && echo -e "${RED}错误：请使用sudo或以root运行${NC}" && exit 1
}

# 检查是否已安装
check_installed() {
    systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null || [ -f "$INSTALL_DIR/ani-rss.jar" ]
}

# 已安装：仅更新管理脚本
update_management_script() {
    echo -e "${YELLOW}检测到 ani-rss 已安装，更新管理脚本...${NC}"
    if ! wget -q "https://github.com/${GITHUB_REPO}/raw/master/linux/ani-rss.sh" -O "/usr/local/bin/ani-rss"; then
        echo -e "${RED}下载管理脚本失败${NC}"
        exit 1
    fi
    chmod +x /usr/local/bin/ani-rss

    mkdir -p "$INSTALL_DIR"
    echo "$GITHUB_REPO" > "$REPO_FILE"

    echo -e "${GREEN}管理脚本已更新，现在可以使用 ani-rss 管理服务${NC}"
    echo ""
    ani-rss
    exit 0
}

# 选择仓库源
select_repo() {
    echo -e "${YELLOW}请选择安装版本:${NC}"
    echo -e "  ${CYAN}1)${NC} 分支版 (Maolaohei/ani-rss) - AList上传修复等优化"
    echo -e "  ${CYAN}2)${NC} 官方版 (wushuo894/ani-rss) - 原版官方仓库"
    echo ""
    read -p "请选择 [1/2] (默认1): " repo_choice
    case "$repo_choice" in
        2)
            GITHUB_REPO="$REPO_OFFICIAL"
            echo -e "${GREEN}已选择: 官方版${NC}"
            ;;
        *)
            GITHUB_REPO="$REPO_FORK"
            echo -e "${GREEN}已选择: 分支版${NC}"
            ;;
    esac
    echo ""
}

# 选择运行模式
select_run_mode() {
    echo -e "${YELLOW}请选择运行模式:${NC}"
    echo -e "  ${CYAN}1)${NC} ${BOLD}root 模式${NC} - 以 root 用户运行 (适合 Docker、简单部署)"
    echo -e "  ${CYAN}2)${NC} ${BOLD}服务用户模式${NC} - 创建专用 ani-rss 用户 (推荐生产环境)"
    echo ""
    read -p "请选择 [1/2] (默认1): " mode_choice
    case "$mode_choice" in
        2)
            RUN_MODE="user"
            SERVICE_USER="ani-rss"
            echo -e "${GREEN}已选择: 服务用户模式${NC}"
            ;;
        *)
            RUN_MODE="root"
            SERVICE_USER="root"
            echo -e "${GREEN}已选择: root 模式${NC}"
            ;;
    esac
    echo ""
}

# 安装JDK
install_jdk() {
    echo -e "${YELLOW}正在检查Java环境...${NC}"
    if command -v java >/dev/null 2>&1; then
        echo -e "${GREEN}检测到JDK已安装${NC}"
        return
    fi

    echo -e "${YELLOW}正在安装OpenJDK...${NC}"
    if command -v apt >/dev/null 2>&1; then
        apt update -qq
        # 优先安装 LTS 版本，逐包尝试（不同发行版源可用包不同）
        for pkg in openjdk-21-jdk openjdk-17-jdk openjdk-25-jdk; do
            if apt install -y "$pkg" >/dev/null 2>&1; then
                break
            fi
        done
    elif command -v yum >/dev/null 2>&1; then
        for pkg in java-21-openjdk-devel java-17-openjdk-devel java-25-openjdk-devel; do
            if yum install -y "$pkg" >/dev/null 2>&1; then
                break
            fi
        done
    else
        echo -e "${RED}不支持的Linux发行版${NC}"
        exit 1
    fi

    ! command -v java >/dev/null 2>&1 && echo -e "${RED}JDK安装失败${NC}" && exit 1
}

# 创建专用用户 (仅用户模式)
create_user() {
    if [ "$RUN_MODE" = "root" ]; then
        echo -e "${GREEN}root 模式，跳过用户创建${NC}"
        return
    fi

    if id "$SERVICE_USER" &>/dev/null; then
        echo -e "${GREEN}用户 $SERVICE_USER 已存在${NC}"
    else
        useradd -r -s /bin/false "$SERVICE_USER" && \
        echo -e "${GREEN}已创建系统用户 $SERVICE_USER${NC}" || {
            echo -e "${RED}用户创建失败${NC}"
            exit 1
        }
    fi
}

# 部署应用文件
deploy_app() {
    echo -e "${YELLOW}正在部署应用程序...${NC}"
    mkdir -p "$INSTALL_DIR" || exit 1

    echo "正在下载 ani-rss.jar"
    if ! wget -q "https://github.com/${GITHUB_REPO}/releases/latest/download/ani-rss.jar" -O "$INSTALL_DIR/ani-rss.jar"; then
        echo -e "${RED}下载 ani-rss.jar 失败${NC}"
        exit 1
    fi
    echo "下载完成 ani-rss.jar"

    echo "正在下载 run.sh"
    if ! wget -q "https://github.com/${GITHUB_REPO}/raw/master/docker/run.sh" -O "$INSTALL_DIR/run.sh"; then
        echo -e "${RED}下载启动脚本失败${NC}"
        exit 1
    fi
    echo "下载完成 run.sh"

    echo "正在下载管理脚本"
    if ! wget -q "https://github.com/${GITHUB_REPO}/raw/master/linux/ani-rss.sh" -O "/usr/local/bin/ani-rss"; then
        echo -e "${RED}下载管理脚本失败${NC}"
        exit 1
    fi
    echo "下载完成管理脚本"

    chmod +x /usr/local/bin/ani-rss

    # 保存配置
    echo "$GITHUB_REPO" > "$REPO_FILE"
    echo "$RUN_MODE" > "$RUN_MODE_FILE"
    echo "$NETWORK_OPTS" > "$INSTALL_DIR/network.conf"

    # 设置权限
    if [ "$RUN_MODE" = "root" ]; then
        chmod 755 "$INSTALL_DIR"
        chmod 755 "$INSTALL_DIR/run.sh"
    else
        chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
        chmod 750 "$INSTALL_DIR"
        chmod 770 "$INSTALL_DIR/run.sh"
    fi
    echo -e "${GREEN}程序部署完成${NC}"
}

# 配置端口
configure_port() {
    echo -e "${YELLOW}正在配置端口...${NC}"
    echo -e "当前默认端口: ${CYAN}$SERVER_PORT${NC}"
    read -p "是否使用默认端口 $SERVER_PORT? [Y/n]: " choice
    case "$choice" in
        [Nn]*)
            while true; do
                read -p "请输入端口号(1-65535): " input_port
                if [[ "$input_port" =~ ^[0-9]+$ ]] && [ "$input_port" -ge 1 ] && [ "$input_port" -le 65535 ]; then
                    SERVER_PORT="$input_port"
                    break
                else
                    echo -e "${RED}端口无效${NC}"
                fi
            done
        ;;
    esac
    echo -e "${GREEN}已选择端口: $SERVER_PORT${NC}"
    echo ""
}

# 配置网络协议优先级
configure_network() {
    echo -e "${YELLOW}正在配置网络协议...${NC}"
    echo -e "  ${CYAN}1)${NC} IPv4 优先 (推荐海外VPS, 解决连接超时问题)"
    echo -e "  ${CYAN}2)${NC} IPv6 优先 (默认)"
    echo ""
    read -p "请选择 [1/2] (默认2): " network_choice
    case "$network_choice" in
        1)
            NETWORK_OPTS="-Djava.net.preferIPv4Stack=true"
            echo -e "${GREEN}已选择: IPv4 优先${NC}"
            ;;
        *)
            NETWORK_OPTS=""
            echo -e "${GREEN}已选择: IPv6 优先 (默认)${NC}"
            ;;
    esac
    echo ""
}

# 配置系统服务
setup_service() {
    echo -e "${YELLOW}正在配置系统服务...${NC}"

    local user_line=""
    local group_line=""
    if [ "$RUN_MODE" = "user" ]; then
        user_line="User=$SERVICE_USER"
        group_line="Group=$SERVICE_USER"
    fi

    tee /etc/systemd/system/"$SERVICE_NAME" > /dev/null <<EOF
[Unit]
Description=ANI-RSS Service
After=network.target

[Service]
Type=simple
${user_line}
${group_line}
WorkingDirectory=$INSTALL_DIR
ExecStart=/bin/bash $INSTALL_DIR/run.sh
Restart=on-failure
RestartSec=30
LimitNOFILE=65535
Environment="TZ=Asia/Shanghai"
# 默认仅本机监听；需要局域网/公网访问时改为 0.0.0.0
Environment="SERVER_ADDRESS=127.0.0.1"
Environment="SERVER_PORT=$SERVER_PORT"
Environment="CONFIG=$INSTALL_DIR/config"
Environment="SWAGGER_ENABLED=false"
Environment="MCP_ENABLED=false"
Environment="JAVA_OPTS=-Xms64m -Xmx512m -Xss256k -XX:+UseG1GC $NETWORK_OPTS"

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    systemctl enable "$SERVICE_NAME" > /dev/null 2>&1

    if ! systemctl start "$SERVICE_NAME"; then
        echo -e "${RED}服务启动失败，请检查日志：journalctl -u $SERVICE_NAME${NC}"
        exit 1
    fi
    echo -e "${GREEN}系统服务配置完成${NC}"
}

# 验证安装
verify_install() {
    echo -e "\n${YELLOW}验证安装...${NC}"
    if ! systemctl is-active "$SERVICE_NAME" | grep -q "active"; then
        echo -e "${RED}服务未正常运行${NC}"
        exit 1
    fi
    echo -e "${GREEN}验证通过，服务运行正常${NC}"
}

# 显示访问信息
show_info() {
    local ip=$(hostname -I | awk '{print $1}')
    echo ""
    echo -e "${GREEN}${BOLD}═══════════════════════════════════════════${NC}"
    echo -e "${GREEN}${BOLD}  安装完成！${NC}"
    echo -e "${GREEN}${BOLD}═══════════════════════════════════════════${NC}"
    echo -e "  URL:      ${CYAN}http://$ip:$SERVER_PORT${NC}"
    echo -e "  用户名:   ${CYAN}admin${NC}"
    echo -e "  初始密码: ${CYAN}admin${NC}"
    echo -e "  运行模式: ${CYAN}$([ "$RUN_MODE" = "root" ] && echo "root" || echo "服务用户")${NC}"
    echo -e "${RED}  请务必及时修改默认用户名与密码${NC}"
    echo -e "${GREEN}${BOLD}═══════════════════════════════════════════${NC}"
    echo ""
    echo -e "使用 ${CYAN}ani-rss${NC} 打开管理菜单"
    echo ""
}

# 主流程
main() {
    show_banner
    check_root
    select_repo

    if check_installed; then
        update_management_script
    fi

    select_run_mode
    install_jdk
    create_user
    configure_port
    configure_network
    deploy_app
    setup_service
    verify_install
    show_info
}

main
