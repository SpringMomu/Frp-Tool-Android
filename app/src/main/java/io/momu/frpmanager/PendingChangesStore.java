package io.momu.frpmanager;

import android.content.Context;
import android.content.SharedPreferences;
import io.momu.frpmanager.manager.util.FrpProfileKey;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Encrypted local draft storage shared by dashboard and manager activity. */
public final class PendingChangesStore {
    private static final String PREFS = "FrpPendingChanges";
    private static final String KEY = "profiles";
    private PendingChangesStore() { }

    public static List<FrpProfile> load(Context context) {
        List<FrpProfile> profiles = new ArrayList<>();
        try {
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = new SecureValueStore(context, preferences).getString(KEY, "[]");
            JSONArray values = new JSONArray(raw);
            for (int index = 0; index < values.length(); index++) {
                JSONObject item = values.getJSONObject(index);
                FrpProfile profile = new FrpProfile();
                profile.setServerAddr(item.optString("serverAddr", ""));
                profile.setServerPort(item.optInt("serverPort", 7000));
                profile.setToken(item.optString("token", ""));
                profile.setRemotePort(item.getInt("remotePort"));
                profile.setLocalIp(item.optString("localIp", "127.0.0.1"));
                profile.setLocalPort(item.getInt("localPort"));
                profile.setProtocol(item.optString("protocol", "tcp"));
                profile.setTag(item.optString("tag", ""));
                profile.setProxyProtocolVersion(item.optString("proxyVersion", ""));
                profile.setSourcePath(item.optString("sourcePath", ""));
                profile.setUnitName(item.optString("unitName", ""));
                profile.setRawConfigContent(item.optString("rawConfig", ""));
				if (profile.isPersisted()) {
					profile.setPersistedRemotePort(item.optInt("persistedRemotePort", profile.getRemotePort()));
					profile.setPersistedProtocol(item.optString("persistedProtocol", profile.getProtocol()));
				}
				profile.setLegacy(item.optBoolean("legacy", false));
				profile.setConfigFileDisabled(item.optBoolean("configDisabled", false));
                profile.setStatus("已停止 (待应用)");
                profile.setModified(true);
                profiles.add(profile);
            }
        } catch (Exception ignored) {
            // A corrupt local draft must never produce remote operations. Start with an empty draft instead.
        }
        return profiles;
    }

    public static void save(Context context, List<FrpProfile> profiles) {
        try {
            JSONArray values = new JSONArray();
            for (FrpProfile profile : profiles) {
                JSONObject item = new JSONObject();
                item.put("serverAddr", profile.getServerAddr()); item.put("serverPort", profile.getServerPort());
                item.put("token", profile.getToken()); item.put("remotePort", profile.getRemotePort());
                item.put("localIp", profile.getLocalIp()); item.put("localPort", profile.getLocalPort());
                item.put("protocol", profile.getProtocol()); item.put("tag", profile.getTag());
                item.put("proxyVersion", profile.getProxyProtocolVersion()); item.put("sourcePath", profile.getSourcePath());
                item.put("unitName", profile.getUnitName()); item.put("rawConfig", profile.getRawConfigContent());
				item.put("persistedRemotePort", profile.getPersistedRemotePort());
				item.put("persistedProtocol", profile.getPersistedProtocol());
				item.put("legacy", profile.isLegacy());
				item.put("configDisabled", profile.isConfigFileDisabled());
				values.put(item);
            }
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            new SecureValueStore(context, preferences).putString(KEY, values.toString());
        } catch (Exception exception) {
            throw new IllegalStateException("无法安全保存待应用配置", exception);
        }
    }

    public static List<String> getDisplayKeys(Context context) {
        List<String> values = new ArrayList<>();
        for (FrpProfile profile : load(context)) values.add(FrpProfileKey.from(profile));
        return values;
    }
}
