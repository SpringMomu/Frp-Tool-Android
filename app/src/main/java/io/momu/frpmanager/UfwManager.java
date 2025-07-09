package io.momu.frpmanager;

import java.util.Locale;

public class UfwManager implements FirewallManager {
    @Override
    public String addPortRuleCommand(int port, String protocol) {
        return String.format(Locale.US, "sudo ufw allow %d/%s comment 'FRP Manager'", port, protocol);
    }

    @Override
    public String removePortRuleCommand(int port, String protocol) {
        return String.format(Locale.US, "sudo ufw delete allow %d/%s", port, protocol);
    }

    @Override
    public String reloadFirewallCommand() {
        return "echo 'UFW规则已自动更新，无需手动重载。'";
    }

    @Override
    public String getType() {
        return "UFW";
    }
}