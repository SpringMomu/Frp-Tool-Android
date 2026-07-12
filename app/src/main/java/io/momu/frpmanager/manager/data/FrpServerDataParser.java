package io.momu.frpmanager.manager.data;

import android.util.Log;
import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.manager.model.BatchOperationBlock;
import io.momu.frpmanager.manager.model.PortExecutionStatus;
import io.momu.frpmanager.manager.util.FrpProfileKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Strict parser for the manager-owned line protocol and INI metadata. */
public class FrpServerDataParser {

    private static final String TAG = "FrpServerDataParser";
    private static final Pattern FILE_NAME = Pattern.compile("^/etc/frp/conf\\.d/port_(\\d+)(?:_(tcp|udp))?\\.ini(?:\\.disabled)?$");
    private static final Pattern UNIT_NAME = Pattern.compile("^frpc@(\\d+)(?:_(tcp|udp))?\\.service$");

    public List<FrpProfile> parseLoadedProfiles(String result, Map<String, Boolean> selectionState,
        List<FrpProfile> oldProfiles, List<FrpProfile> pendingProfiles, Set<String> lockedProfiles) throws IOException {
        if (result == null) throw new IOException("服务器未返回端口配置数据");
        List<FrpProfile> loaded = new ArrayList<>();
        Set<String> runningUnits = new HashSet<>();
        Map<String, String> unitStates = new HashMap<>();

        for (String rawLine : result.split("\\r?\\n")) {
			if (rawLine.startsWith("FRP_FILE\t")) {
				String[] columns = rawLine.split("\t", 3);
                if (columns.length != 3) throw new IOException("服务器返回的配置记录格式无效");
                String path = decode(columns[1]);
                String content = decode(columns[2]);
                FrpProfile profile = parseProfile(path, content);
                if (profile != null) loaded.add(profile);
			} else if (rawLine.startsWith("FRP_RUNNING\t")) {
				runningUnits.add(rawLine.substring("FRP_RUNNING\t".length()).trim());
			} else if (rawLine.startsWith("FRP_UNIT\t")) {
				String[] columns = rawLine.split("\t", 3);
                if (columns.length == 3) unitStates.put(columns[1].trim(), columns[2].trim());
            }
        }

        Map<String, Long> duplicateCounts = loaded.stream().collect(Collectors.groupingBy(FrpProfileKey::from, Collectors.counting()));
        for (FrpProfile profile : loaded) {
            String key = FrpProfileKey.from(profile);
            if (duplicateCounts.getOrDefault(key, 0L) > 1) {
                profile.setStatus("配置冲突");
                profile.setLastError("多个文件声明了相同的端口/协议，已禁止自动操作");
            } else if (profile.getLastError() == null && profile.getManagedLocalFrpsSafetyError() != null) {
                profile.setStatus("受管映射冲突");
                profile.setLastError(profile.getManagedLocalFrpsSafetyError());
            } else if (profile.isConfigFileDisabled()) {
                profile.setStatus("已禁用（配置文件已禁用）");
            } else if (profile.getLastError() == null) {
                String unit = profile.getUnitName();
                if (runningUnits.contains(unit)) {
                    profile.setStatus("运行中 (注册未验证)");
                    profile.setProxyRegistrationState("未验证");
                } else if ("enabled".equals(unitStates.get(unit))) {
                    profile.setStatus("已停止");
                } else if ("disabled".equals(unitStates.get(unit))) {
                    profile.setStatus("已禁用");
                } else {
                    profile.setStatus("已停止");
                }
            }
            profile.setSelected(selectionState.getOrDefault(key, false));
            profile.setModified(false);
            profile.setFirewallStatus(FrpProfile.FIREWALL_UNKNOWN);
        }

        // A port rename has a new display key but still owns the old persisted file.
        // Remove both identities so the draft appears as one editable row.
        Map<String, FrpProfile> pendingByKey = pendingProfiles.stream()
            .collect(Collectors.toMap(FrpProfileKey::from, profile -> profile, (left, right) -> right));
        Set<String> replacedKeys = new HashSet<>(pendingByKey.keySet());
        for (FrpProfile pending : pendingByKey.values()) {
            if (!pending.getPersistedIdentityKey().isEmpty()) replacedKeys.add(pending.getPersistedIdentityKey());
        }
        loaded.removeIf(profile -> replacedKeys.contains(FrpProfileKey.from(profile)));
        for (FrpProfile pending : pendingByKey.values()) {
            pending.setModified(true);
            String persistedKey = pending.getPersistedIdentityKey();
            pending.setSelected(selectionState.getOrDefault(FrpProfileKey.from(pending),
                selectionState.getOrDefault(persistedKey, false)));
            pending.setFirewallStatus(FrpProfile.FIREWALL_PENDING);
            loaded.add(pending);
        }

        Map<String, FrpProfile> oldByKey = oldProfiles.stream()
            .collect(Collectors.toMap(FrpProfileKey::from, profile -> profile, (left, right) -> right));
        for (FrpProfile profile : loaded) {
            String key = FrpProfileKey.from(profile);
            if (lockedProfiles.contains(key) && oldByKey.containsKey(key)) profile.setStatus(oldByKey.get(key).getStatus());
        }
        return loaded;
    }

    /** Only reports manager-owned rules, never claims that a port is globally reachable. */
    public void applyFirewallRuleStatuses(List<FrpProfile> profiles, String firewallOutput) {
        Set<String> ownedRules = new HashSet<>();
        if (firewallOutput != null) {
            Matcher matcher = Pattern.compile("frp-tool:([0-9]+)/(tcp|udp)", Pattern.CASE_INSENSITIVE).matcher(firewallOutput);
            while (matcher.find()) ownedRules.add(FrpProfileKey.from(Integer.parseInt(matcher.group(1)), matcher.group(2)));
        }
        for (FrpProfile profile : profiles) {
            if (!profile.isModified()) {
                profile.setFirewallStatus(ownedRules.contains(FrpProfileKey.from(profile))
                    ? FrpProfile.FIREWALL_ALLOWED : FrpProfile.FIREWALL_NOT_ALLOWED);
            }
        }
    }

    public BatchOutputParseResult parseBatchOperationOutput(String output) {
        List<BatchOperationBlock> blocks = new ArrayList<>();
        Map<String, PortExecutionStatus> statuses = new LinkedHashMap<>();
        if (output == null || output.trim().isEmpty()) return new BatchOutputParseResult(blocks, statuses);
		Pattern header = Pattern.compile("(?m)^=+ PROCESSING PORT (\\d+)/(TCP|UDP) .*?=+\\s*$");
		Matcher matcher = header.matcher(output);
		int start = 0;
		boolean foundHeader = false;
		while (matcher.find()) {
			if (foundHeader) addBlock(blocks, statuses, output.substring(start, matcher.start()));
			start = matcher.start();
			foundHeader = true;
		}
		if (!foundHeader) {
			addBlock(blocks, statuses, output);
		} else {
			addBlock(blocks, statuses, output.substring(start));
		}
        return new BatchOutputParseResult(blocks, statuses);
    }

    public List<String> parseFailedProfileKeys(String verifyOutput) {
        List<String> failures = new ArrayList<>();
        if (verifyOutput == null) return failures;
        Matcher failed = Pattern.compile("(?m)^(?:FAIL|APPLY_FAIL):([0-9]+/(?:tcp|udp))$").matcher(verifyOutput);
        while (failed.find()) failures.add(failed.group(1));
        return failures;
    }

    public List<String> parseSuccessfulProfileKeys(String output) {
        List<String> success = new ArrayList<>();
        if (output == null) return success;
        Matcher matcher = Pattern.compile("(?m)^APPLY_OK:([0-9]+/(?:tcp|udp))$").matcher(output);
        while (matcher.find()) success.add(matcher.group(1));
        return success;
    }

    private void addBlock(List<BatchOperationBlock> blocks, Map<String, PortExecutionStatus> statuses, String block) {
        if (block == null || block.trim().isEmpty()) return;
        Matcher keyMatcher = Pattern.compile("(?m)^FRP_MANAGER_KEY:([0-9]+/(?:tcp|udp))$").matcher(block);
        String key = keyMatcher.find() ? keyMatcher.group(1) : null;
        PortExecutionStatus status = block.contains("FRP_MANAGER_STATUS:ERROR") ? PortExecutionStatus.ERROR
            : block.contains("FRP_MANAGER_STATUS:WARNING") ? PortExecutionStatus.WARNING
            : block.contains("FRP_MANAGER_STATUS:SUCCESS") ? PortExecutionStatus.SUCCESS : PortExecutionStatus.UNKNOWN;
        Matcher reasonMatcher = Pattern.compile("(?m)^FRP_MANAGER_REASON:([A-Z_]+)$").matcher(block);
        String reason = reasonMatcher.find() ? reasonMatcher.group(1) : null;
        if (key != null) statuses.put(key, status);
        blocks.add(new BatchOperationBlock(key, block, status, reason));
    }

    private FrpProfile parseProfile(String path, String content) throws IOException {
        Matcher filename = FILE_NAME.matcher(path);
        if (!filename.matches()) throw new IOException("不受管理的配置路径：" + path);
        FrpProfile profile = parseFrpProfileFromContent(content);
        if (profile == null) throw new IOException("无法解析配置文件：" + path);
        int filePort = Integer.parseInt(filename.group(1));
        String fileProtocol = filename.group(2) == null ? "tcp" : filename.group(2).toLowerCase(Locale.US);
        if (profile.getRemotePort() != filePort || !fileProtocol.equals(normalizeProtocol(profile.getProtocol()))) {
            profile.setStatus("配置冲突");
            profile.setLastError("文件名与 remote_port/type 不一致，已禁止自动操作");
        }
        profile.setSourcePath(path);
        profile.setRawConfigContent(content);
        profile.setPersistedRemotePort(filePort);
        profile.setPersistedProtocol(fileProtocol);
        profile.setLegacy(filename.group(2) == null);
		profile.setConfigFileDisabled(path.endsWith(".ini.disabled"));
        profile.setUnitName(filename.group(2) == null ? "frpc@" + filePort + ".service"
            : "frpc@" + filePort + "_" + fileProtocol + ".service");
        return profile;
    }

    private FrpProfile parseFrpProfileFromContent(String content) {
        try {
            FrpProfile profile = new FrpProfile();
            String serverAddr = parseValue(content, "server_addr"); if (!serverAddr.isEmpty()) profile.setServerAddr(serverAddr);
            String serverPort = parseValue(content, "server_port"); if (!serverPort.isEmpty()) profile.setServerPort(Integer.parseInt(serverPort));
            String token = parseValue(content, "token"); if (!token.isEmpty()) profile.setToken(token);
            String remotePort = parseValue(content, "remote_port"); if (remotePort.isEmpty()) return null; profile.setRemotePort(Integer.parseInt(remotePort));
            String localIp = parseValue(content, "local_ip"); if (!localIp.isEmpty()) profile.setLocalIp(localIp);
            String localPort = parseValue(content, "local_port"); if (!localPort.isEmpty()) profile.setLocalPort(Integer.parseInt(localPort));
            String protocol = parseValue(content, "type"); if (protocol.isEmpty()) return null; profile.setProtocol(normalizeProtocol(protocol));
            profile.setTag(parseCommentValue(content, "tag"));
            String proxy = parseValue(content, "proxy_protocol_version"); if (!proxy.isEmpty()) profile.setProxyProtocolVersion(proxy);
            return profile;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Ignoring malformed FRP profile metadata", exception);
            return null;
        }
    }

    private String parseValue(String content, String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*(.*)$").matcher(content);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String parseCommentValue(String content, String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*#\\s*" + Pattern.quote(key) + "\\s*=\\s*(.*)$").matcher(content);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String decode(String encoded) throws IOException {
        try { return new String(java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException exception) { throw new IOException("服务器返回的 Base64 数据无效", exception); }
    }
    private String normalizeProtocol(String value) { return value == null ? "tcp" : value.toLowerCase(Locale.US); }

    public static class BatchOutputParseResult {
        private final List<BatchOperationBlock> blocks;
        private final Map<String, PortExecutionStatus> statusByProfileKey;
        public BatchOutputParseResult(List<BatchOperationBlock> blocks, Map<String, PortExecutionStatus> statusByProfileKey) {
            this.blocks = blocks; this.statusByProfileKey = statusByProfileKey;
        }
        public List<BatchOperationBlock> getBlocks() { return blocks; }
        public Map<String, PortExecutionStatus> getStatusByProfileKey() { return statusByProfileKey; }
    }
}
