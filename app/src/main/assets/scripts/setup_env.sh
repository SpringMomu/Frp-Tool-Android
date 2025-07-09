#!/bin/bash
set -e
FRPC_PATH="/usr/local/bin/frpc"
FRP_DIR="/etc/frp/conf.d"
SYSTEMD_SERVICE="/etc/systemd/system/frpc@.service"
FRPC_TEMP_PATH="/root/frpc_temp_upload"
echo "PROGRESS:10;STATUS:正在创建配置目录..."
if [ -d "$FRP_DIR" ]; then
    echo "PROGRESS:25;STATUS:配置目录已存在, 跳过。"
else
    mkdir -p "$FRP_DIR"
    echo "PROGRESS:25;STATUS:配置目录 /etc/frp/conf.d 创建成功。"
fi
sleep 1
echo "PROGRESS:30;STATUS:正在安装 frpc..."
if [ ! -f "$FRPC_TEMP_PATH" ]; then
    echo "ERROR:未在 $FRPC_TEMP_PATH 找到 frpc 文件，请确保App已上传。"
    exit 1
fi
if [ -x "$FRPC_PATH" ]; then
    echo "PROGRESS:50;STATUS:frpc 已安装, 跳过。"
else
    mv "$FRPC_TEMP_PATH" "$FRPC_PATH"
    chmod +x "$FRPC_PATH"
    echo "PROGRESS:50;STATUS:frpc 安装并设为可执行。"
fi
"$FRPC_PATH" --version
sleep 1
echo "PROGRESS:60;STATUS:正在配置 systemd 服务..."
if [ -f "$SYSTEMD_SERVICE" ]; then
    echo "PROGRESS:85;STATUS:systemd 服务文件已存在, 跳过。"
else
    tee "$SYSTEMD_SERVICE" > /dev/null <<'EOF'
[Unit]
Description=FRP Client Service for Port %i
After=network.target

[Service]
Type=simple
User=root
ExecStart=/usr/local/bin/frpc -c /etc/frp/conf.d/port_%i.ini
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF
    echo "PROGRESS:80;STATUS:systemd 服务 frpc@.service 创建成功。"
    echo "PROGRESS:85;STATUS:正在重载 systemd..."
    systemctl daemon-reload
    sleep 1
fi
echo "PROGRESS:100;STATUS:所有配置已完成！"
echo "FINAL_STATUS:SETUP_COMPLETE"