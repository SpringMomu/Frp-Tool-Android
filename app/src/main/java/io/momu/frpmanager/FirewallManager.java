package io.momu.frpmanager;

import java.io.IOException;

public interface FirewallManager {
    String addPortRuleCommand(int port, String protocol);
    String removePortRuleCommand(int port, String protocol);
    String reloadFirewallCommand();
    String getType();
}