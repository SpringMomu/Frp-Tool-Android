package io.momu.frpmanager;

public class FirewallManagerFactory {
    public static FirewallManager getManager(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }

        switch (type.toLowerCase()) {
            case "firewalld":
                return new FirewallDManager();
            case "ufw":
                return new UfwManager();
            case "iptables":
                return new IptablesManager();
            default:
                return null;
        }
    }
}
