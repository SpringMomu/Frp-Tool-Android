package io.momu.frpmanager;

import java.util.Locale;

public class FirewallDManager implements FirewallManager {
    private final String zone;

    public FirewallDManager() { this("public"); }
    public FirewallDManager(String zone) { this.zone = zone; }

    @Override
    public String ruleExistsCommand(int port, String protocol) {
        return String.format(Locale.US,
            "FRP_RULE_FOUND=1; for FAM in ipv4 ipv6; do "
            + "if firewall-cmd --permanent --direct --query-rule $FAM filter FRP_TOOL 0 -p %s --dport %d "
            + "-m comment --comment frp-tool:%d/%s -j ACCEPT >/dev/null 2>&1; then FRP_RULE_FOUND=0; fi; "
            + "done; [ \"$FRP_RULE_FOUND\" -eq 0 ]",
            protocol, port, port, protocol);
    }

    @Override
    public String addPortRuleCommand(int port, String protocol) {
        return String.format(Locale.US,
            "for FAM in ipv4 ipv6; do firewall-cmd --permanent --direct --add-chain $FAM filter FRP_TOOL 2>/dev/null || true; "
            + "firewall-cmd --permanent --direct --query-rule $FAM filter INPUT 0 -j FRP_TOOL >/dev/null 2>&1 "
            + "|| firewall-cmd --permanent --direct --add-rule $FAM filter INPUT 0 -j FRP_TOOL; "
            + "firewall-cmd --permanent --direct --query-rule $FAM filter FRP_TOOL 0 -p %s --dport %d -m comment --comment frp-tool:%d/%s -j ACCEPT >/dev/null 2>&1 "
            + "|| firewall-cmd --permanent --direct --add-rule $FAM filter FRP_TOOL 0 -p %s --dport %d -m comment --comment frp-tool:%d/%s -j ACCEPT; done",
            protocol, port, port, protocol, protocol, port, port, protocol);
    }

    @Override
    public String removePortRuleCommand(int port, String protocol) {
        return String.format(Locale.US,
            "for FAM in ipv4 ipv6; do while firewall-cmd --permanent --direct --remove-rule $FAM filter FRP_TOOL 0 -p %s --dport %d -m comment --comment frp-tool:%d/%s -j ACCEPT 2>/dev/null; do :; done; done",
            protocol, port, port, protocol);
    }

    @Override
    public String reloadFirewallCommand() {
        return "firewall-cmd --reload --quiet";
    }

    @Override
    public String getType() {
        return "Firewalld";
    }
}
