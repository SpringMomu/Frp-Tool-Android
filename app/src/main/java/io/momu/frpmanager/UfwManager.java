package io.momu.frpmanager;

import java.util.Locale;

public class UfwManager implements FirewallManager {
    @Override
    public String ruleExistsCommand(int port, String protocol) {
        return String.format(Locale.US,
            "ufw status numbered | grep -F 'frp-tool:%d/%s' | "
            + "grep -Eq '^\\[[[:space:]]*[0-9]+\\][[:space:]]+%d/%s([[:space:]]+\\(v6\\))?[[:space:]]+ALLOW IN'",
            port, protocol, port, protocol);
    }

    @Override
    public String addPortRuleCommand(int port, String protocol) {
        return String.format(Locale.US,
            "ufw status numbered | grep -Fq 'frp-tool:%d/%s' || ufw allow %d/%s comment 'frp-tool:%d/%s'",
            port, protocol, port, protocol, port, protocol);
    }

    @Override
    public String removePortRuleCommand(int port, String protocol) {
        return String.format(Locale.US,
            "ufw status numbered | grep -F 'frp-tool:%d/%s' "
            + "| sed -n 's/^\\[[[:space:]]*\\([0-9][0-9]*\\)\\].*/\\1/p' "
            + "| sort -rn | while read -r n; do [ -n \"$n\" ] && ufw --force delete \"$n\"; done",
            port, protocol);
    }

    @Override
    public String reloadFirewallCommand() {
        return "ufw reload";
    }

    @Override
    public String getType() {
        return "UFW";
    }
}
