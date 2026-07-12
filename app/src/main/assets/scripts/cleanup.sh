#!/bin/bash
# 仅清理带明确 Frp Tool ownership 标记的资源。
set -eu

echo "--- 开始清理FRP Manager环境 ---"

FRP_CONF_DIR="/etc/frp/conf.d"
echo "正在枚举本工具拥有的配置..."
if [ -d "$FRP_CONF_DIR" ]; then
    find "$FRP_CONF_DIR" -maxdepth 1 -type f \( -name 'port_*.ini' -o -name 'port_*.ini.disabled' \) -print | while IFS= read -r config; do
        if grep -Fqx '# Frp Tool managed' "$config"; then
            base=$(basename "$config")
            base=${base%.disabled}
            base=${base%.ini}
            instance=${base#port_}
            echo "停止并删除本工具配置: $config"
            systemctl disable --now "frpc@${instance}.service" 2>/dev/null || true
            rm -f "$config"
        fi
    done
fi

SYSTEMD_SERVICE="/etc/systemd/system/frpc@.service"
if [ -f "$SYSTEMD_SERVICE" ] && grep -Fqx '# Managed by Frp Tool' "$SYSTEMD_SERVICE"; then
    rm -f "$SYSTEMD_SERVICE"
    echo "$SYSTEMD_SERVICE 已删除。"
fi

FRPS_SERVICE="/etc/systemd/system/frps.service"
FRPS_CONFIG="/etc/frp/frps.toml"
FRPS_TOKEN_FILE="/etc/frp/frps.token"
if [ -f "$FRPS_SERVICE" ] && grep -Fqx '# Frp Tool embedded frps service v1' "$FRPS_SERVICE"; then
    echo "停止并删除受管 frps.service"
    systemctl disable --now frps.service 2>/dev/null || true
    rm -f "$FRPS_SERVICE"
fi
if [ -f "$FRPS_CONFIG" ] && grep -Fqx '# Frp Tool embedded frps v1' "$FRPS_CONFIG"; then
    rm -f "$FRPS_CONFIG" "$FRPS_TOKEN_FILE"
    echo "受管 frps 配置和 token 已删除。"
fi

systemctl daemon-reload
echo "Systemd已重载。"

if [ -d "$FRP_CONF_DIR" ] && [ -z "$(find "$FRP_CONF_DIR" -mindepth 1 -print -quit)" ]; then
	    rmdir "$FRP_CONF_DIR"
	    rmdir /etc/frp 2>/dev/null || true
	    echo "空的本工具配置目录已删除。"
fi

echo "保留 /usr/local/bin/frpc 和 /usr/local/bin/frps，避免删除其他部署共用的二进制。"

echo "--- 清理完成 ---"
