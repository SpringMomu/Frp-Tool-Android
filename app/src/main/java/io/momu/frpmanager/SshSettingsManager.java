package io.momu.frpmanager;

import android.content.Context;
import android.content.SharedPreferences;

public class SshSettingsManager {

	private static final String PREFS_NAME = "SshSettingsPrefs";
	private static final String KEY_HOST = "ssh_host";
	private static final String KEY_PORT = "ssh_port";
	private static final String KEY_USERNAME = "ssh_username";
	private static final String KEY_CONFIGURED = "ssh_configured";
	private static final String KEY_PASSWORD = "ssh_password";
	private static final String KEY_USE_KEY_AUTH = "ssh_use_key_auth";
	private static final String KEY_PRIVATE_KEY = "ssh_private_key";
	private static final String KEY_PASSPHRASE = "ssh_passphrase";
	private static final String KEY_HOST_KEY_PREFIX = "ssh_host_key_";
	public static final String KEY_FRP_SERVER_ADDR = "frp_server_addr";
	public static final String KEY_FRP_SERVER_PORT = "frp_server_port";
	public static final String KEY_FRP_TOKEN = "frp_token";
	private static final String KEY_MANAGE_FIREWALL = "ssh_manage_firewall";
	private static final String KEY_FRPS_FIREWALL_HOST = "frps_firewall_host";
	private static final String KEY_FRPS_FIREWALL_PORT = "frps_firewall_port";
	private static final String KEY_EMBEDDED_FRPS_MANAGED = "embedded_frps_managed";

	private final SharedPreferences sharedPreferences;
	private final SecureValueStore secureValues;

	public SshSettingsManager(Context context) {
		this.sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		this.secureValues = new SecureValueStore(context, sharedPreferences);
	}

	public void savePasswordAuthSettings(String host, int port, String username, String password) {
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putString(KEY_HOST, host);
		editor.putInt(KEY_PORT, port);
		editor.putString(KEY_USERNAME, username);
		secureValues.putString(KEY_PASSWORD, password);
		editor.putBoolean(KEY_CONFIGURED, true);
		editor.putBoolean(KEY_USE_KEY_AUTH, false); 

		secureValues.remove(KEY_PRIVATE_KEY);
		secureValues.remove(KEY_PASSPHRASE);

		editor.apply();
	}

	public void saveKeyAuthSettings(String host, int port, String username, String privateKeyContent, String passphrase) {
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putString(KEY_HOST, host);
		editor.putInt(KEY_PORT, port);
		editor.putString(KEY_USERNAME, username);
		secureValues.putString(KEY_PRIVATE_KEY, privateKeyContent);
		secureValues.putString(KEY_PASSPHRASE, passphrase);
		editor.putBoolean(KEY_CONFIGURED, true);
		editor.putBoolean(KEY_USE_KEY_AUTH, true); 

		secureValues.remove(KEY_PASSWORD);

		editor.apply();
	}


	public void saveFrpCommonSettings(String serverAddr, int serverPort, String token) {
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putString(KEY_FRP_SERVER_ADDR, serverAddr);
		editor.putInt(KEY_FRP_SERVER_PORT, serverPort);
		secureValues.putString(KEY_FRP_TOKEN, token);
		editor.apply();
	}

	public void setManageFirewall(boolean manage) {
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putBoolean(KEY_MANAGE_FIREWALL, manage);
		editor.apply();
	}

	/** Optional override for the SSH endpoint that owns the frps firewall. */
	public void saveFrpsFirewallSettings(String host, int port) {
		sharedPreferences.edit()
			.putString(KEY_FRPS_FIREWALL_HOST, host == null ? "" : host.trim())
			.putInt(KEY_FRPS_FIREWALL_PORT, port)
			.apply();
	}

	public String getFrpsFirewallHost() {
		return sharedPreferences.getString(KEY_FRPS_FIREWALL_HOST, "");
	}

	public int getFrpsFirewallPort() {
		return sharedPreferences.getInt(KEY_FRPS_FIREWALL_PORT, 22);
	}

	public boolean hasFrpsFirewallConnection() {
		String host = getFrpsFirewallHost();
		return host != null && !host.trim().isEmpty() && getFrpsFirewallPort() > 0 && getFrpsFirewallPort() <= 65535;
	}

	/**
	 * Prefer the explicitly supplied frps management host. When it is omitted,
	 * the explicitly configured FRP server is the only valid fallback. The SSH
	 * host runs the uploaded frpc and must never be guessed to be frps.
	 */
	public String getFirewallHost() {
		return getFirewallHost(getHost());
	}

	public String getFirewallHost(String fallbackSshHost) {
		String explicitHost = getFrpsFirewallHost();
		if (explicitHost != null && !explicitHost.trim().isEmpty()) {
			return explicitHost.trim();
		}
		if (isEmbeddedFrpsManaged()) {
			return fallbackSshHost == null ? "" : fallbackSshHost.trim();
		}
		String serverAddr = sharedPreferences.getString(KEY_FRP_SERVER_ADDR, "");
		if (serverAddr != null && !serverAddr.trim().isEmpty()) {
			return serverAddr.trim();
		}
		return "";
	}

	public int getFirewallPort() {
		String firewallHost = getFirewallHost();
		String sshHost = getHost();
		if (!hasFrpsFirewallConnection() && firewallHost != null && sshHost != null
			&& firewallHost.equalsIgnoreCase(sshHost.trim())) {
			return getPort();
		}
		int port = getFrpsFirewallPort();
		return port > 0 && port <= 65535 ? port : 22;
	}

	private String hostKeyPreferenceKey(String host, int port) {
		return KEY_HOST_KEY_PREFIX + (host == null ? "" : host.trim().toLowerCase()) + "_" + port;
	}

	public String getHostKeyFingerprint(String host, int port) {
		return sharedPreferences.getString(hostKeyPreferenceKey(host, port), null);
	}

	public void trustHostKeyFingerprint(String host, int port, String fingerprint) {
		if (fingerprint == null || fingerprint.trim().isEmpty()) {
			throw new IllegalArgumentException("SSH 主机指纹不能为空");
		}
		sharedPreferences.edit().putString(hostKeyPreferenceKey(host, port), fingerprint.trim()).apply();
	}

	public String getHost() {
		return sharedPreferences.getString(KEY_HOST, null);
	}

	public int getPort() {
		return sharedPreferences.getInt(KEY_PORT, 22);
	}

	public String getUsername() {
		return sharedPreferences.getString(KEY_USERNAME, "root"); 
	}

	public String getPassword() {
		return secureValues.getString(KEY_PASSWORD, null);
	}

	public boolean getUseKeyAuth() {
		return sharedPreferences.getBoolean(KEY_USE_KEY_AUTH, false);
	}

	public String getPrivateKey() {
		return secureValues.getString(KEY_PRIVATE_KEY, null);
	}

	public String getPassphrase() {
		return secureValues.getString(KEY_PASSPHRASE, "");
	}

	public boolean isFirewallManaged() {
		return sharedPreferences.getBoolean(KEY_MANAGE_FIREWALL, false);
	}

	public boolean isConfigured() {
		return sharedPreferences.getBoolean(KEY_CONFIGURED, false) &&
			getHost() != null && !getHost().isEmpty() &&
			getUsername() != null && !getUsername().isEmpty();
	}

	public String getFrpServerAddr() {
		return sharedPreferences.getString(KEY_FRP_SERVER_ADDR, defaultFrpServerAddress(getHost()));
	}

	static String defaultFrpServerAddress(String sshHost) {
		// The managed SSH host receives frpc. It is not evidence that frps runs there.
		return "";
	}

	public int getFrpServerPort() {
		return sharedPreferences.getInt(KEY_FRP_SERVER_PORT, 7000);
	}

	public String getFrpToken() {
		return secureValues.getString(KEY_FRP_TOKEN, "");
	}

	public boolean hasFrpCommonSettings() {
		String address = getFrpServerAddr();
		int port = getFrpServerPort();
		return sharedPreferences.contains(KEY_FRP_SERVER_ADDR)
			&& address != null && !address.trim().isEmpty()
			&& port > 0 && port <= 65535;
	}

	public void setEmbeddedFrpsManaged(boolean managed) {
		sharedPreferences.edit().putBoolean(KEY_EMBEDDED_FRPS_MANAGED, managed).apply();
	}

	public boolean isEmbeddedFrpsManaged() {
		if (sharedPreferences.contains(KEY_EMBEDDED_FRPS_MANAGED)) {
			return sharedPreferences.getBoolean(KEY_EMBEDDED_FRPS_MANAGED, true);
		}
		String address = getFrpServerAddr();
		String sshHost = getHost();
		return address == null || address.trim().isEmpty()
			|| "127.0.0.1".equals(address.trim()) || "localhost".equalsIgnoreCase(address.trim())
			|| (sshHost != null && address.trim().equalsIgnoreCase(sshHost.trim()));
	}
}
