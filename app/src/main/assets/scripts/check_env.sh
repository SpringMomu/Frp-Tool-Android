#!/bin/bash

echo "--- FRP Manager 一体化环境检查 V6 ---"
echo "执行用户: $(whoami)"
echo ""

FRP_VERSION="0.63.0"
FRPC_PATH="/usr/local/bin/frpc"
FRPS_PATH="/usr/local/bin/frps"
FRP_DIR="/etc/frp"
FRPC_CONFIG_DIR="$FRP_DIR/conf.d"
FRPS_CONFIG="$FRP_DIR/frps.toml"
FRPS_TOKEN_FILE="$FRP_DIR/frps.token"
FRPC_SERVICE="/etc/systemd/system/frpc@.service"
FRPS_SERVICE="/etc/systemd/system/frps.service"
DEPLOYMENT_MODE="${FRP_TOOL_MODE:-embedded}"

BINARIES_OK=false
DIRECTORIES_OK=false
SERVICES_OK=false
FRPS_CONFIG_OK=false
MANAGED_CONFIGS_OK=true
RUNTIME_OK=false

echo "[1/5] 检查 App 上传的 FRP 双组件..."
if [ "$DEPLOYMENT_MODE" = external ]; then
    if [ -x "$FRPC_PATH" ] && "$FRPC_PATH" --version 2>/dev/null | grep -Fq "$FRP_VERSION" \
        && "$FRPC_PATH" verify --help >/dev/null 2>&1; then
        echo "状态: 外部模式的 frpc $FRP_VERSION 与校验能力正常。"
        BINARIES_OK=true
    else
        echo "状态: frpc 缺失、版本不匹配或不可执行。"
    fi
else
    if [ -x "$FRPC_PATH" ] && [ -x "$FRPS_PATH" ] \
        && "$FRPC_PATH" --version 2>/dev/null | grep -Fq "$FRP_VERSION" \
        && "$FRPS_PATH" --version 2>/dev/null | grep -Fq "$FRP_VERSION" \
        && "$FRPC_PATH" verify --help >/dev/null 2>&1 \
        && "$FRPS_PATH" verify --help >/dev/null 2>&1; then
        echo "状态: frpc + frps 均为 $FRP_VERSION，且校验能力正常。"
        BINARIES_OK=true
    else
        echo "状态: frpc 或 frps 缺失、版本不匹配或不可执行。"
    fi
fi
echo ""

echo "[2/5] 检查受管目录..."
if [ -d "$FRP_DIR" ] && [ -d "$FRPC_CONFIG_DIR" ]; then
    echo "状态: $FRP_DIR 与端口配置目录存在。"
    DIRECTORIES_OK=true
else
    echo "状态: 受管目录不完整。"
fi
echo ""

echo "[3/5] 检查 systemd 服务模板..."
if [ "$DEPLOYMENT_MODE" = external ]; then
    if [ -f "$FRPC_SERVICE" ] && grep -Eq '# Frp Tool service v(2|3)' "$FRPC_SERVICE" \
        && grep -Fq 'ExecStart=/usr/local/bin/frpc -c /etc/frp/conf.d/port_%i.ini' "$FRPC_SERVICE"; then
        echo "状态: 外部模式的 frpc@.service 内容正确。"
        SERVICES_OK=true
    else
        echo "状态: frpc@.service 缺失或内容错误。"
    fi
else
    if [ -f "$FRPC_SERVICE" ] && grep -Fq '# Frp Tool service v3' "$FRPC_SERVICE" \
        && grep -Fq 'ExecStart=/usr/local/bin/frpc -c /etc/frp/conf.d/port_%i.ini' "$FRPC_SERVICE" \
        && grep -Fq 'StartLimitBurst=3' "$FRPC_SERVICE" \
        && [ -f "$FRPS_SERVICE" ] && grep -Fq '# Frp Tool embedded frps service v1' "$FRPS_SERVICE" \
        && grep -Fq 'ExecStart=/usr/local/bin/frps -c /etc/frp/frps.toml' "$FRPS_SERVICE"; then
        echo "状态: frps.service 与 frpc@.service 内容正确。"
        SERVICES_OK=true
    else
        echo "状态: 一体化 systemd 服务缺失或版本过旧。"
    fi
fi
echo ""

echo "[4/5] 检查 frps 鉴权与本工具端口配置..."
if [ "$DEPLOYMENT_MODE" = external ]; then
    FRPS_CONFIG_OK=true
    if [ -d "$FRPC_CONFIG_DIR" ]; then
        while IFS= read -r config; do
            grep -Fqx '# Frp Tool managed' "$config" || continue
            "$FRPC_PATH" verify -c "$config" >/dev/null 2>&1 || MANAGED_CONFIGS_OK=false
        done < <(find "$FRPC_CONFIG_DIR" -maxdepth 1 -type f \( -name 'port_*.ini' -o -name 'port_*.ini.disabled' \) -print 2>/dev/null)
    fi
elif [ -f "$FRPS_CONFIG" ] && grep -Fqx '# Frp Tool embedded frps v1' "$FRPS_CONFIG" \
    && grep -Fq 'bindAddr = "127.0.0.1"' "$FRPS_CONFIG" \
    && grep -Fq 'bindPort = 7000' "$FRPS_CONFIG" \
    && grep -Fq 'proxyBindAddr = "0.0.0.0"' "$FRPS_CONFIG" \
    && [ -f "$FRPS_TOKEN_FILE" ] && grep -Eq '^[0-9a-f]{64}$' "$FRPS_TOKEN_FILE" \
    && [ "$(stat -c %a "$FRPS_TOKEN_FILE" 2>/dev/null)" = 600 ] \
    && "$FRPS_PATH" verify -c "$FRPS_CONFIG" >/dev/null 2>&1; then
    FRPS_TOKEN=$(cat "$FRPS_TOKEN_FILE")
    if grep -Fq "auth.token = \"$FRPS_TOKEN\"" "$FRPS_CONFIG"; then FRPS_CONFIG_OK=true; fi
fi

if [ "$DEPLOYMENT_MODE" != external ] && [ -d "$FRPC_CONFIG_DIR" ] && [ -f "$FRPS_TOKEN_FILE" ]; then
    FRPS_TOKEN=${FRPS_TOKEN:-$(cat "$FRPS_TOKEN_FILE")}
    while IFS= read -r config; do
        grep -Fqx '# Frp Tool managed' "$config" || continue
        local_port=$(awk -F= '/^[[:space:]]*local_port[[:space:]]*=/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$config")
        remote_port=$(awk -F= '/^[[:space:]]*remote_port[[:space:]]*=/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$config")
        if { [ "$remote_port" = 7000 ] || [ "$local_port" = "$remote_port" ] || [ "$local_port" = 7000 ]; } \
            && [ -n "$local_port" ] && [ -n "$remote_port" ] && [[ "$config" != *.disabled ]]; then
            echo "状态: 检测到同机端口冲突配置 $config，必须先修正本地端口或避开 7000。"
            MANAGED_CONFIGS_OK=false
            continue
        fi
        grep -Eq '^[[:space:]]*server_addr[[:space:]]*=[[:space:]]*127\.0\.0\.1[[:space:]]*$' "$config" \
            && grep -Eq '^[[:space:]]*server_port[[:space:]]*=[[:space:]]*7000[[:space:]]*$' "$config" \
            && grep -Fq "token = $FRPS_TOKEN" "$config" \
            && "$FRPC_PATH" verify -c "$config" >/dev/null 2>&1 \
            || MANAGED_CONFIGS_OK=false
    done < <(find "$FRPC_CONFIG_DIR" -maxdepth 1 -type f \( -name 'port_*.ini' -o -name 'port_*.ini.disabled' \) -print 2>/dev/null)
fi

if $FRPS_CONFIG_OK && $MANAGED_CONFIGS_OK; then
    if [ "$DEPLOYMENT_MODE" = external ]; then
        echo "状态: 外部 frps 模式，本工具端口配置均通过校验。"
    else
        echo "状态: 强 token、回环控制端和本工具端口连接配置一致。"
    fi
else
    echo "状态: frps 鉴权配置缺失，或已有本工具端口尚未迁移。"
fi
echo ""

echo "[5/5] 检查受管 frps 运行状态..."
if [ "$DEPLOYMENT_MODE" = external ]; then
    echo "状态: 外部模式不要求当前 SSH 主机运行 frps。"
    RUNTIME_OK=true
elif systemctl is-enabled --quiet frps.service 2>/dev/null \
    && systemctl is-active --quiet frps.service 2>/dev/null \
    && timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/7000' 2>/dev/null; then
    echo "状态: frps 已启用，控制端仅监听 127.0.0.1:7000，代理端口按规则对外监听。"
    RUNTIME_OK=true
else
    echo "状态: 受管 frps 未启用、未运行或控制端不可达。"
fi
echo ""

echo "--- 检查结论 ---"
if $BINARIES_OK && $DIRECTORIES_OK && $SERVICES_OK && $FRPS_CONFIG_OK && $MANAGED_CONFIGS_OK && $RUNTIME_OK; then
    echo "一体化 FRP 环境已就绪，可直接管理端口与防火墙。"
    echo "STATUS:ALL_OK"
else
    echo "一体化组件不完整，需要执行环境初始化或修复。"
    echo "STATUS:NEEDS_SETUP"
fi
