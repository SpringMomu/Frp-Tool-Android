#!/bin/bash
echo "--- 开始清理FRP Manager环境 ---"
echo "正在停止并禁用所有 frpc@*.service..."
systemctl stop 'frpc@*.service'
systemctl disable 'frpc@*.service'
echo "服务已停止并禁用。"
SYSTEMD_SERVICE="/etc/systemd/system/frpc@.service"
if [ -f "$SYSTEMD_SERVICE" ]; then
    rm -f "$SYSTEMD_SERVICE"
    echo "$SYSTEMD_SERVICE 已删除。"
fi
systemctl daemon-reload
echo "Systemd已重载。"
FRP_DIR="/etc/frp"
if [ -d "$FRP_DIR" ]; then
    rm -rf "$FRP_DIR"
    echo "目录 $FRP_DIR 已删除。"
fi
FRPC_PATH="/usr/local/bin/frpc"
if [ -f "$FRPC_PATH" ]; then
    rm -f "$FRPC_PATH"
    echo "二进制文件 $FRPC_PATH 已删除。"
fi
echo "--- 清理完成 ---"