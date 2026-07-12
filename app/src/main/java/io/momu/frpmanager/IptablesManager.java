package io.momu.frpmanager;

import java.util.Locale;

public class IptablesManager implements FirewallManager {
    @Override
    public String ruleExistsCommand(int port, String protocol) {
        return String.format(Locale.US,
            "FRP_RULE_FOUND=1; for IPT in iptables ip6tables; do command -v $IPT >/dev/null 2>&1 || continue; "
            + "if $IPT -C FRP_TOOL -p %s --dport %d -m comment --comment 'frp-tool:%d/%s' -j ACCEPT 2>/dev/null; "
            + "then FRP_RULE_FOUND=0; fi; done; [ \"$FRP_RULE_FOUND\" -eq 0 ]",
            protocol, port, port, protocol);
    }

    @Override
    public String addPortRuleCommand(int port, String protocol) {
        return String.format(Locale.US,
            "for IPT in iptables ip6tables; do command -v $IPT >/dev/null 2>&1 || continue; "
            + "$IPT -N FRP_TOOL 2>/dev/null || true; "
            + "$IPT -C INPUT -j FRP_TOOL 2>/dev/null || $IPT -I INPUT 1 -j FRP_TOOL; "
            + "$IPT -C FRP_TOOL -p %s --dport %d -m comment --comment 'frp-tool:%d/%s' -j ACCEPT 2>/dev/null "
            + "|| $IPT -I FRP_TOOL 1 -p %s --dport %d -m comment --comment 'frp-tool:%d/%s' -j ACCEPT; done",
            protocol, port, port, protocol, protocol, port, port, protocol);
    }

    @Override
    public String removePortRuleCommand(int port, String protocol) {
        return String.format(Locale.US,
            "for IPT in iptables ip6tables; do command -v $IPT >/dev/null 2>&1 || continue; "
            + "while $IPT -D FRP_TOOL -p %s --dport %d -m comment --comment 'frp-tool:%d/%s' -j ACCEPT 2>/dev/null; do :; done; done",
            protocol, port, port, protocol);
    }

    @Override
    public String reloadFirewallCommand() {
        return "if command -v netfilter-persistent >/dev/null 2>&1; then netfilter-persistent save; "
            + "elif [ -d /etc/sysconfig ]; then iptables-save > /etc/sysconfig/iptables; "
            + "if command -v ip6tables-save >/dev/null 2>&1; then ip6tables-save > /etc/sysconfig/ip6tables; fi; "
            + "else echo '无法验证 iptables 持久化机制，拒绝将临时规则标记为成功' >&2; exit 1; fi";
    }

    @Override
    public String getType() {
        return "Iptables";
    }
}
