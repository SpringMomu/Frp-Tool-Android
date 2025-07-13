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
	public static final String KEY_FRP_SERVER_ADDR = "frp_server_addr";
	public static final String KEY_FRP_SERVER_PORT = "frp_server_port";
	public static final String KEY_FRP_TOKEN = "frp_token";
	private static final String KEY_MANAGE_FIREWALL = "ssh_manage_firewall";

	private final SharedPreferences sharedPreferences;

	public SshSettingsManager(Context context) {
		this.sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
	}

	public void savePasswordAuthSettings(String host, int port, String username, String password) {
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putString(KEY_HOST, host);
		editor.putInt(KEY_PORT, port);
		editor.putString(KEY_USERNAME, username);
		editor.putString(KEY_PASSWORD, password);
		editor.putBoolean(KEY_CONFIGURED, true);
		editor.putBoolean(KEY_USE_KEY_AUTH, false); 

		editor.remove(KEY_PRIVATE_KEY);
		editor.remove(KEY_PASSPHRASE);

		editor.apply();
	}

	public void saveKeyAuthSettings(String host, int port, String username, String privateKeyContent, String passphrase) {
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putString(KEY_HOST, host);
		editor.putInt(KEY_PORT, port);
		editor.putString(KEY_USERNAME, username);
		editor.putString(KEY_PRIVATE_KEY, privateKeyContent);
		editor.putString(KEY_PASSPHRASE, passphrase);
		editor.putBoolean(KEY_CONFIGURED, true);
		editor.putBoolean(KEY_USE_KEY_AUTH, true); 

		editor.remove(KEY_PASSWORD);

		editor.apply();
	}


	public void saveFrpCommonSettings(String serverAddr, int serverPort, String token) {
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putString(KEY_FRP_SERVER_ADDR, serverAddr);
		editor.putInt(KEY_FRP_SERVER_PORT, serverPort);
		editor.putString(KEY_FRP_TOKEN, token);
		editor.apply();
	}

	public void setManageFirewall(boolean manage) {
		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.putBoolean(KEY_MANAGE_FIREWALL, manage);
		editor.apply();
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
		return sharedPreferences.getString(KEY_PASSWORD, null);
	}

	public boolean getUseKeyAuth() {
		return sharedPreferences.getBoolean(KEY_USE_KEY_AUTH, false);
	}

	public String getPrivateKey() {
		return sharedPreferences.getString(KEY_PRIVATE_KEY, null);
	}

	public String getPassphrase() {
		return sharedPreferences.getString(KEY_PASSPHRASE, "");
	}

	public boolean isFirewallManaged() {
		return sharedPreferences.getBoolean(KEY_MANAGE_FIREWALL, true); 
	}

	public boolean isConfigured() {
		return sharedPreferences.getBoolean(KEY_CONFIGURED, false) &&
			getHost() != null && !getHost().isEmpty() &&
			getUsername() != null && !getUsername().isEmpty();
	}

	public String getFrpServerAddr() {
		return sharedPreferences.getString(KEY_FRP_SERVER_ADDR, getHost());
	}

	public int getFrpServerPort() {
		return sharedPreferences.getInt(KEY_FRP_SERVER_PORT, 7000);
	}

	public String getFrpToken() {
		return sharedPreferences.getString(KEY_FRP_TOKEN, "");
	}
}