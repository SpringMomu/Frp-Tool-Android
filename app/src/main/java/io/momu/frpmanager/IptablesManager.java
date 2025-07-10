package io.momu.frpmanager;

import java.util.Locale;

public class IptablesManager implements FirewallManager {
    @Override
    public String addPortRuleCommand(int port, String protocol) {
        return String.format(Locale.US,
							 "sudo iptables -C INPUT -p %s --dport %d -j ACCEPT 2>/dev/null || sudo iptables -A INPUT -p %s --dport %d -j ACCEPT",
							 protocol, port, protocol, port);
    }

    @Override
    public String removePortRuleCommand(int port, String protocol) {
        return String.format(Locale.US,
							 "sudo iptables -D INPUT -p %s --dport %d -j ACCEPT 2>/dev/null || true",
							 protocol, port);
    }

    @Override
    public String reloadFirewallCommand() {
        return "if command -v iptables-save >/dev/null 2>&1; then sudo iptables-save | sudo tee /etc/sysconfig/iptables >/dev/null; echo 'iptables规则已保存(CentOS/RHEL)。'; " +
			"elif command -v netfilter-persistent >/dev/null 2>&1; then sudo netfilter-persistent save >/dev/null; echo 'iptables规则已保存(Debian/Ubuntu)。'; " +
			"else echo '警告：无法自动保存iptables规则，请手动保存以确保持久化！'; fi";
    }

    @Override
    public String getType() {
        return "Iptables";
    }
}