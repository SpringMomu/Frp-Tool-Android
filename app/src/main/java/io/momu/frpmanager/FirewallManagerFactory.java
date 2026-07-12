package io.momu.frpmanager;

public class FirewallManagerFactory {
    public static FirewallManager getManager(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }

		String normalized = type.toLowerCase();
		switch (normalized) {
			case "firewalld":
				return new FirewallDManager();
            case "ufw":
                return new UfwManager();
            case "iptables":
                return new IptablesManager();
			default:
				if (normalized.startsWith("firewalld:")) {
					String zone = type.substring(type.indexOf(':') + 1).trim();
					return zone.matches("[A-Za-z0-9_.-]+") ? new FirewallDManager(zone) : null;
				}
				return null;
        }
    }
}
