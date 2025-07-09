#!/bin/bash
echo "--- FRP Manager 环境检查 ---"
echo "执行用户: $(whoami)"
echo "系统PATH: $PATH"
echo ""
FRPC_PATH="/usr/local/bin/frpc"
FRP_DIR="/etc/frp/conf.d"
SYSTEMD_SERVICE="/etc/systemd/system/frpc@.service"
FRPC_OK=false
DIR_OK=false
SERVICE_OK=false
echo "[1/3] 检查 frpc 程序..."
echo "路径: $FRPC_PATH"
if [ -x "$FRPC_PATH" ]; then
    echo "状态: 已找到，且可执行。"
    FRPC_OK=true
else
    echo "状态: 缺失或无执行权限。"
fi
echo ""
echo "[2/3] 检查 frp 配置目录..."
echo "路径: $FRP_DIR"
if [ -d "$FRP_DIR" ]; then
    echo "状态: 已找到。"
    DIR_OK=true
else
    echo "状态: 缺失。"
fi
echo ""
echo "[3/3] 检查 systemd 服务..."
echo "路径: $SYSTEMD_SERVICE"
if [ -f "$SYSTEMD_SERVICE" ]; then
    echo "状态: 已找到。"
    SERVICE_OK=true
else
    echo "状态: 缺失。"
fi
echo ""
echo "--- 检查结论 ---"
if $FRPC_OK && $DIR_OK && $SERVICE_OK; then
    echo "所有组件均已正确配置。"
    echo "STATUS:ALL_OK"
else
    echo "存在缺失的组件，需要执行配置向导。"
    echo "STATUS:NEEDS_SETUP"
fi