package io.momu.frpmanager;

import java.io.IOException;

public interface FirewallManager {
    /** Returns a shell command whose exit status is zero only when the exact manager-owned rule exists. */
    String ruleExistsCommand(int port, String protocol);
    String addPortRuleCommand(int port, String protocol);
    String removePortRuleCommand(int port, String protocol);
    String reloadFirewallCommand();
    String getType();
}
