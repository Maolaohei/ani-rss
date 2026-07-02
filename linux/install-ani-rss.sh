#!/bin/bash
# ANI-RSS 一体化安装脚本 with Systemd 服务
# 适用系统: Ubuntu/Debian/CentOS/RHEL

# 定义颜色代码
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m'

# 定义常量
INSTALL_DIR="/opt/ani-rss"
SERVICE_USER="ani-rss"
SERVICE_NAME="ani-rss.service"
SERVER_PORT="7789"
REPO_FILE="$INSTALL_DIR/repo.conf"

# 仓库源定义
REPO_OFFICIAL="wushuo894/ani-rss"
REPO_FORK="Maolaohei/ani-rss"

# 选择仓库源
select_repo() {
    echo -e "${YELLOW}请选择安装版本:${NC}"
    echo "  1) 分支版 (Maolaohei/ani-rss) - AList上传修复等优化"
    echo "  2) 官方版 (wushuo894/ani-rss) - 原版官方仓库"
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

    # 保存仓库源配置
    mkdir -p "$INSTALL_DIR"
    echo "$GITHUB_REPO" > "$REPO_FILE"

    echo -e "${GREEN}管理脚本已更新，现在可以使用 ani-rss switch 切换版本${NC}"
    ani-rss help
    exit 0
}

# 检查root权限
check_root() {
    [ "$EUID" -ne 0 ] && echo -e "${RED}错误：请使用sudo或以root运行${NC}" && exit 1
}

# 安装JDK
install_jdk() {
    echo -e "${YELLOW}正在检查Java环境...${NC}"
    if command -v java >/dev/null 2>&1; then
        echo -e "${GREEN}检测到JDK已安装${NC}"
        return
    fi

    echo -e "${YELLOW}正在安装OpenJDK 25...${NC}"
    if command -v apt >/dev/null 2>&1; then
        apt update -qq && apt install -y openjdk-25-jdk
    elif command -v yum >/dev/null 2>&1; then
        yum install -y java-25-openjdk-devel
    else
        echo -e "${RED}不支持的Linux发行版${NC}"
        exit 1
    fi

    ! command -v java >/dev/null 2>&1 && echo -e "${RED}JDK安装失败${NC}" && exit 1
}

# 创建专用用户
create_user() {
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

    echo "正在下载 ani-rss.sh"
    if ! wget -q "https://github.com/${GITHUB_REPO}/raw/master/linux/ani-rss.sh" -O "/usr/local/bin/ani-rss"; then
        echo -e "${RED}下载启动脚本失败${NC}"
        exit 1
    fi
    echo "下载完成 ani-rss.sh"

    sudo chmod +x /usr/local/bin/ani-rss

    # 保存仓库源配置
    echo "$GITHUB_REPO" > "$REPO_FILE"

    # 保存网络配置
    echo "$NETWORK_OPTS" > "$INSTALL_DIR/network.conf"

    # 设置权限
    chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"
    chmod 750 "$INSTALL_DIR"
    chmod 770 "$INSTALL_DIR/run.sh"
    echo -e "${GREEN}程序部署完成${NC}"
}

configure_port() {
    echo -e "${YELLOW}正在配置端口...${NC}"
    echo -e "当前默认端口: $SERVER_PORT"
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
}

# 配置网络协议优先级
configure_network() {
    echo -e "${YELLOW}正在配置网络协议...${NC}"
    echo "  1) IPv4 优先 (推荐海外VPS, 解决连接超时问题)"
    echo "  2) IPv6 优先 (默认)"
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
}

# 配置系统服务
setup_service() {
    echo -e "${YELLOW}正在配置系统服务...${NC}"
    tee /etc/systemd/system/"$SERVICE_NAME" > /dev/null <<EOF
[Unit]
Description=ANI-RSS Service
After=network.target

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_USER
WorkingDirectory=$INSTALL_DIR
ExecStart=/bin/bash $INSTALL_DIR/run.sh
Restart=on-failure
RestartSec=30
LimitNOFILE=65535
Environment="TZ=Asia/Shanghai"
Environment="SERVER_ADDRESS=0.0.0.0"
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
    IP=$(hostname -I | awk '{print $1}')
    echo -e "\n${GREEN}安装完成！访问信息："
    echo -e "URL: http://$IP:$SERVER_PORT"
    echo -e "用户名: admin"
    echo -e "初始密码: admin${NC}"
    echo -e "${RED}请务必及时修改默认用户名与密码${NC}"
    ani-rss help
}

# 主流程
main() {
    check_root
    select_repo
    if check_installed; then
        update_management_script
    fi
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
