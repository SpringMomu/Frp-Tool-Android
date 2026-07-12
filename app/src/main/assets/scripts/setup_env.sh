#!/bin/bash
set -euo pipefail

FRP_VERSION="0.63.0"
FRPC_PATH="/usr/local/bin/frpc"
FRPS_PATH="/usr/local/bin/frps"
FRP_DIR="/etc/frp"
FRPC_CONFIG_DIR="$FRP_DIR/conf.d"
FRPS_CONFIG="$FRP_DIR/frps.toml"
FRPS_TOKEN_FILE="$FRP_DIR/frps.token"
FRPC_SERVICE="/etc/systemd/system/frpc@.service"
FRPS_SERVICE="/etc/systemd/system/frps.service"
FRPC_TEMP_PATH="/root/frpc_temp_upload"
FRPC_SHA_PATH="/root/frpc_temp_upload.sha256"
FRPS_TEMP_PATH="/root/frps_temp_upload"
FRPS_SHA_PATH="/root/frps_temp_upload.sha256"
MIGRATION_DIR=""

cleanup() {
    rm -f "$FRPC_TEMP_PATH" "$FRPC_SHA_PATH" "$FRPS_TEMP_PATH" "$FRPS_SHA_PATH"
    if [ -n "$MIGRATION_DIR" ] && [ -d "$MIGRATION_DIR" ]; then rm -rf "$MIGRATION_DIR"; fi
}
trap cleanup EXIT

fail() {
    echo "ERROR:$1"
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "服务器缺少必需命令: $1"
}

verify_upload() {
    local binary="$1"
    local checksum_file="$2"
    local label="$3"
    [ -f "$binary" ] && [ -f "$checksum_file" ] || fail "未找到 App 上传的 $label 或校验文件。"
    local expected actual
    expected=$(awk 'NR==1 {print $1}' "$checksum_file")
    actual=$(sha256sum "$binary" | awk '{print $1}')
    [ -n "$expected" ] && [ "$expected" = "$actual" ] || fail "$label SHA-256 校验失败。"
}

echo "PROGRESS:5;STATUS:正在检查 App 上传的 FRP 组件..."
for required in awk base64 bash file install mktemp od sha256sum systemctl timeout; do require_command "$required"; done
verify_upload "$FRPC_TEMP_PATH" "$FRPC_SHA_PATH" "frpc"
verify_upload "$FRPS_TEMP_PATH" "$FRPS_SHA_PATH" "frps"

ARCH=$(uname -m)
case "$ARCH" in aarch64|x86_64) ;; *) fail "不支持的 CPU 架构: $ARCH" ;; esac
ARCH_PATTERN=$( [ "$ARCH" = aarch64 ] && echo 'aarch64|ARM aarch64' || echo 'x86-64' )
file "$FRPC_TEMP_PATH" | grep -Eq "$ARCH_PATTERN" || fail "上传的 frpc 与服务器架构不匹配。"
file "$FRPS_TEMP_PATH" | grep -Eq "$ARCH_PATTERN" || fail "上传的 frps 与服务器架构不匹配。"

# Refuse collisions before writing either binary or a managed configuration.
if [ -e "$FRPS_CONFIG" ] && ! grep -Fqx '# Frp Tool embedded frps v1' "$FRPS_CONFIG"; then
    fail "$FRPS_CONFIG 已存在且不属于 Frp Tool，拒绝覆盖。请先备份或选择外部 frps 模式。"
fi
if [ -e "$FRPS_SERVICE" ] && ! grep -Fqx '# Frp Tool embedded frps service v1' "$FRPS_SERVICE"; then
    fail "$FRPS_SERVICE 已存在且不属于 Frp Tool，拒绝覆盖。"
fi
if systemctl is-active --quiet frps.service 2>/dev/null \
    && ! { [ -f "$FRPS_SERVICE" ] && grep -Fqx '# Frp Tool embedded frps service v1' "$FRPS_SERVICE"; }; then
    fail "检测到运行中的非受管 frps.service，拒绝覆盖。请使用外部 frps 模式。"
fi
if [ -e "$FRPS_PATH" ]; then
    existing_frps_hash=$(sha256sum "$FRPS_PATH" | awk '{print $1}')
    uploaded_frps_hash=$(sha256sum "$FRPS_TEMP_PATH" | awk '{print $1}')
    [ "$existing_frps_hash" = "$uploaded_frps_hash" ] \
        || fail "$FRPS_PATH 已存在不同的 frps 二进制，拒绝覆盖。请使用外部 frps 模式或先手动备份。"
fi
if timeout 1 bash -c 'exec 3<>/dev/tcp/127.0.0.1/7000' 2>/dev/null \
    && ! { [ -f "$FRPS_SERVICE" ] && grep -Fqx '# Frp Tool embedded frps service v1' "$FRPS_SERVICE" \
        && systemctl is-active --quiet frps.service; }; then
    fail "127.0.0.1:7000 已被非受管服务占用，拒绝覆盖。请使用外部 frps 模式。"
fi

echo "PROGRESS:15;STATUS:正在创建受管 FRP 工作目录..."
install -d -m 0700 "$FRP_DIR"
install -d -m 0700 "$FRPC_CONFIG_DIR"

echo "PROGRESS:25;STATUS:正在部署 App 内置的 frpc 与 frps..."
install -m 0755 "$FRPC_TEMP_PATH" "$FRPC_PATH"
install -m 0755 "$FRPS_TEMP_PATH" "$FRPS_PATH"
"$FRPC_PATH" --version | grep -Fq "$FRP_VERSION" || fail "frpc 版本校验失败。"
"$FRPS_PATH" --version | grep -Fq "$FRP_VERSION" || fail "frps 版本校验失败。"
"$FRPC_PATH" verify --help >/dev/null 2>&1 || fail "frpc 缺少配置校验能力。"
"$FRPS_PATH" verify --help >/dev/null 2>&1 || fail "frps 缺少配置校验能力。"

echo "PROGRESS:35;STATUS:正在生成或恢复本机 frps 鉴权..."
if [ ! -f "$FRPS_TOKEN_FILE" ] || ! grep -Eq '^[0-9a-f]{64}$' "$FRPS_TOKEN_FILE"; then
    umask 077
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$FRPS_TOKEN_FILE"
fi
chmod 0600 "$FRPS_TOKEN_FILE"
FRPS_TOKEN=$(cat "$FRPS_TOKEN_FILE")
[ ${#FRPS_TOKEN} -eq 64 ] || fail "frps token 生成失败。"

echo "PROGRESS:45;STATUS:正在配置回环控制端与对外代理端口..."
FRPS_CONFIG_TMP="$FRPS_CONFIG.frp-tool.$$"
umask 077
{
    echo '# Frp Tool embedded frps v1'
    echo 'bindAddr = "127.0.0.1"'
    echo 'bindPort = 7000'
    echo 'proxyBindAddr = "0.0.0.0"'
    echo 'auth.method = "token"'
    printf 'auth.token = "%s"\n' "$FRPS_TOKEN"
} > "$FRPS_CONFIG_TMP"
"$FRPS_PATH" verify -c "$FRPS_CONFIG_TMP" >/dev/null || fail "生成的 frps 配置校验失败。"
install -m 0600 "$FRPS_CONFIG_TMP" "$FRPS_CONFIG"
rm -f "$FRPS_CONFIG_TMP"

cat > "$FRPS_SERVICE.frp-tool.$$" <<'EOF'
# Frp Tool embedded frps service v1
[Unit]
Description=Frp Tool Embedded FRP Server
Wants=network-online.target
After=network-online.target
StartLimitIntervalSec=60
StartLimitBurst=3

[Service]
Type=simple
User=root
ExecStart=/usr/local/bin/frps -c /etc/frp/frps.toml
Restart=on-failure
RestartSec=10s
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
install -m 0644 "$FRPS_SERVICE.frp-tool.$$" "$FRPS_SERVICE"
rm -f "$FRPS_SERVICE.frp-tool.$$"

cat > "$FRPC_SERVICE.frp-tool.$$" <<'EOF'
# Managed by Frp Tool
# Frp Tool service v3
[Unit]
Description=FRP Client Service for Port %i
Wants=network-online.target
After=network-online.target frps.service
Requires=frps.service
StartLimitIntervalSec=60
StartLimitBurst=3

[Service]
Type=simple
User=root
ExecStart=/usr/local/bin/frpc -c /etc/frp/conf.d/port_%i.ini
Restart=on-failure
RestartSec=10s
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
install -m 0644 "$FRPC_SERVICE.frp-tool.$$" "$FRPC_SERVICE"
rm -f "$FRPC_SERVICE.frp-tool.$$"

echo "PROGRESS:60;STATUS:正在启动受管 frps..."
systemctl daemon-reload
systemctl enable --now frps.service
sleep 1
systemctl is-active --quiet frps.service || fail "frps.service 未能保持运行。"
timeout 5 bash -c 'exec 3<>/dev/tcp/127.0.0.1/7000' 2>/dev/null || fail "frps 本机控制端口 127.0.0.1:7000 不可达。"

prepare_managed_config() {
    local source="$1"
    local target="$2"
    awk -v token="$FRPS_TOKEN" '
        function finish_common() {
            if (!in_common) return
            if (!seen_addr) print "server_addr = 127.0.0.1"
            if (!seen_port) print "server_port = 7000"
            if (!seen_token) print "token = " token
        }
        BEGIN { in_common=0; saw_common=0; seen_addr=0; seen_port=0; seen_token=0 }
        /^[[:space:]]*\[[^]]+\][[:space:]]*$/ {
            finish_common()
            in_common=($0 ~ /^[[:space:]]*\[common\][[:space:]]*$/)
            if (in_common) { saw_common=1; seen_addr=0; seen_port=0; seen_token=0 }
            print
            next
        }
        in_common && /^[[:space:]]*server_addr[[:space:]]*=/ { print "server_addr = 127.0.0.1"; seen_addr=1; next }
        in_common && /^[[:space:]]*server_port[[:space:]]*=/ { print "server_port = 7000"; seen_port=1; next }
        in_common && /^[[:space:]]*token[[:space:]]*=/ { print "token = " token; seen_token=1; next }
        { print }
        END {
            finish_common()
            if (!saw_common) exit 42
        }
    ' "$source" > "$target"
}

managed_config_safety_error() {
    local config="$1"
    local local_port remote_port
    local_port=$(awk -F= '/^[[:space:]]*local_port[[:space:]]*=/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$config")
    remote_port=$(awk -F= '/^[[:space:]]*remote_port[[:space:]]*=/ {gsub(/[[:space:]]/, "", $2); print $2; exit}' "$config")
    [ -n "$local_port" ] && [ -n "$remote_port" ] || return 1
    if [ "$remote_port" = 7000 ]; then
        echo "remote_port 占用了受管 frps 控制端口 7000"
        return 0
    fi
    if [ "$local_port" = "$remote_port" ]; then
        echo "local_port 与 remote_port 相同"
        return 0
    fi
    if [ "$local_port" = 7000 ]; then
        echo "local_port 指向了受管 frps 控制端口 7000"
        return 0
    fi
    return 1
}

echo "PROGRESS:72;STATUS:正在迁移本工具端口配置到受管 frps..."
MIGRATION_DIR=$(mktemp -d /tmp/frp-tool-migrate.XXXXXX)
MANIFEST="$MIGRATION_DIR/manifest"
: > "$MANIFEST"
for config in "$FRPC_CONFIG_DIR"/port_*.ini "$FRPC_CONFIG_DIR"/port_*.ini.disabled; do
    [ -f "$config" ] || continue
    grep -Fqx '# Frp Tool managed' "$config" || continue
    base=$(basename "$config")
    prepared="$MIGRATION_DIR/$base"
    prepare_managed_config "$config" "$prepared" || fail "无法迁移 $config 的 [common] 配置。"
    "$FRPC_PATH" verify -c "$prepared" >/dev/null || fail "迁移后的 $config 未通过 frpc 校验。"
    active=0
    instance=${base#port_}
    instance=${instance%.disabled}
    instance=${instance%.ini}
    if safety_error=$(managed_config_safety_error "$prepared"); then
        echo "迁移保护: $config 的 $safety_error，已停用以避免同机转发冲突。"
        systemctl disable --now "frpc@${instance}.service" 2>/dev/null || true
        if [[ "$config" = *.disabled ]]; then
            install -m 0600 "$prepared" "$config"
        else
            printf '%s\t%s\t%s\n' "$config" "$prepared" "$config.disabled" >> "$MIGRATION_DIR/disabled-manifest"
        fi
        continue
    fi
    systemctl is-active --quiet "frpc@${instance}.service" && active=1 || true
    printf '%s\t%s\t%s\t%s\n' "$config" "$prepared" "$instance" "$active" >> "$MANIFEST"
done

if [ -f "$MIGRATION_DIR/disabled-manifest" ]; then
    while IFS=$'\t' read -r config prepared disabled_config; do
        [ -n "$config" ] || continue
        install -m 0600 "$prepared" "$disabled_config"
        rm -f "$config"
    done < "$MIGRATION_DIR/disabled-manifest"
fi

while IFS=$'\t' read -r config prepared instance active; do
    [ -n "$config" ] || continue
    base=$(basename "$config")
    cp -p "$config" "$MIGRATION_DIR/backup.$base"
    install -m 0600 "$prepared" "$config"
done < "$MANIFEST"

restart_failed=0
while IFS=$'\t' read -r config prepared instance active; do
    [ "$active" = 1 ] || continue
    systemctl restart "frpc@${instance}.service" || restart_failed=1
done < "$MANIFEST"
if [ "$restart_failed" -ne 0 ]; then
    while IFS=$'\t' read -r config prepared instance active; do
        [ -n "$config" ] || continue
        base=$(basename "$config")
        cp -p "$MIGRATION_DIR/backup.$base" "$config"
        [ "$active" = 1 ] && systemctl restart "frpc@${instance}.service" 2>/dev/null || true
    done < "$MANIFEST"
    fail "已有运行中端口迁移失败，配置已回滚。"
fi

echo "PROGRESS:90;STATUS:正在执行最终运行状态校验..."
"$FRPS_PATH" verify -c "$FRPS_CONFIG" >/dev/null
systemctl is-enabled --quiet frps.service
systemctl is-active --quiet frps.service

echo "PROGRESS:100;STATUS:frps 与 frpc 一体化环境已就绪。"
echo "FINAL_STATUS:SETUP_COMPLETE"
