#!/bin/sh

get_value() {
    VAL=$(eval "$1" 2>/dev/null) || VAL="$2"
    if [ -z "$VAL" ]; then
        VAL="$2"
    fi
    echo "$VAL"
}

CPU_USAGE=$(get_value "top -bn1 | grep 'Cpu(s)' | awk '{printf \"%.0f\", \$2 + \$4}'" "0")
MEM_USAGE=$(get_value "free -m | grep Mem | awk '{printf \"%.0f\", \$3/\$2 * 100.0}'" "0")
CPU_TEMP=$(get_value "(for zone in /sys/class/thermal/thermal_zone*; do if [ -f \\\"\$zone/type\\\" ] && grep -q -E \\\"x86_pkg_temp|cpu\\\" \\\"\$zone/type\\\"; then cat \\\"\$zone/temp\\\"; exit; fi; done; if [ -f /sys/class/thermal/thermal_zone0/temp ]; then cat /sys/class/thermal/thermal_zone0/temp; else exit 1; fi)" "0")
CPU_MODEL=$(get_value "grep 'model name' /proc/cpuinfo | head -n 1 | cut -d ':' -f 2 | xargs" "N/A")
MEM_DETAILS=$(get_value "free -h | grep '^Mem:' | awk '{print \$3\" / \"\$2}' | sed 's/i//g'" "N/A")
CORE_COUNT=$(get_value "nproc" "0")

DF_OUT=$(df -hT | grep -vE '^tmpfs|^squashfs|^devtmpfs|^overlay' | sed 's/\\/\\\\/g; s/"/\\"/g' | awk '{printf "%s\\n", $0}')
LSBLK_OUT=$(lsblk -o NAME,SIZE,TYPE,MOUNTPOINT --noheadings | sed 's/\\/\\\\/g; s/"/\\"/g' | awk '{printf "%s\\n", $0}')
FRP_FILES=$(find /etc/frp/conf.d/ -type f \( -name 'port_*.ini' -o -name 'port_*.ini.disabled' \) -printf '%f\\n' 2>/dev/null | sed 's/\\/\\\\/g; s/"/\\"/g' | awk '{printf "%s\\n", $0}')
FRP_RUNNING=$(systemctl list-units --type=service --state=running 'frpc@*.service' --no-pager | grep -oP 'frpc@\K[^.]+' 2>/dev/null | sed 's/\\/\\\\/g; s/"/\\"/g' | awk '{printf "%s\\n", $0}')
FRP_ENABLED=$(systemctl list-unit-files 'frpc@*.service' --no-pager | grep 'enabled' | grep -oP 'frpc@\K[^.]+' 2>/dev/null | sed 's/\\/\\\\/g; s/"/\\"/g' | awk '{printf "%s\\n", $0}')

printf "{\n"
printf "  \"cpuUsage\": %s,\n" "$CPU_USAGE"
printf "  \"memUsage\": %s,\n" "$MEM_USAGE"
printf "  \"cpuTemp\": %s,\n" "$CPU_TEMP"
printf "  \"dfOutput\": \"%s\",\n" "$DF_OUT"
printf "  \"lsblkOutput\": \"%s\",\n" "$LSBLK_OUT"
printf "  \"frpTotalFiles\": \"%s\",\n" "$FRP_FILES"
printf "  \"frpRunningServices\": \"%s\",\n" "$FRP_RUNNING"
printf "  \"frpEnabledServices\": \"%s\",\n" "$FRP_ENABLED"
printf "  \"cpuModel\": \"%s\",\n" "$CPU_MODEL"
printf "  \"memoryDetails\": \"%s\",\n" "$MEM_DETAILS"
printf "  \"coreCount\": %s\n" "$CORE_COUNT"
printf "}\n"