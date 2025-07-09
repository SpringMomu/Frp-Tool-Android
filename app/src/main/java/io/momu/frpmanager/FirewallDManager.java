package io.momu.frpmanager;

import java.util.Locale;

public class FirewallDManager implements FirewallManager {
    @Override
    public String addPortRuleCommand(int port, String protocol) {
        return String.format(Locale.US, "sudo firewall-cmd --permanent --add-port=%d/%s --quiet", port, protocol);
    }

    @Override
    public String removePortRuleCommand(int port, String protocol) {
        return String.format(Locale.US, "sudo firewall-cmd --permanent --remove-port=%d/%s --quiet || true", port, protocol);
    }

    @Override
    public String reloadFirewallCommand() {
        return "sudo firewall-cmd --reload --quiet";
    }

    @Override
    public String getType() {
        return "Firewalld";
    }
}
