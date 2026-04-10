package io.momu.frpmanager.manager.data;

import android.util.Log;
import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.manager.model.BatchOperationBlock;
import io.momu.frpmanager.manager.model.PortExecutionStatus;
import io.momu.frpmanager.manager.util.FrpProfileKey;
import java.io.IOException;
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

public class FrpServerDataParser {

    private static final String TAG = "FrpServerDataParser";
    private static final String SEPARATOR = "---FRP_MANAGER_SEPARATOR---";

    public List<FrpProfile> parseLoadedProfiles(
        String result,
        Map<String, Boolean> selectionState,
        List<FrpProfile> oldProfiles,
        List<FrpProfile> pendingProfiles,
        Set<String> lockedProfiles
    ) throws IOException {
        String[] sections = result.split(SEPARATOR);
        if (sections.length < 3) {
            Log.e(TAG, "Incomplete server data. Only " + sections.length + " sections found. Raw output: " + result);
            throw new IOException("从服务器返回的数据格式不完整");
        }

        List<FrpProfile> profilesFromConfig = parseProfilesFromConfigSection(sections[0]);
        String runningServices = sections[1];
        String enabledStatuses = sections[2];

        Map<String, FrpProfile> masterProfileMap = new HashMap<>();
        for (FrpProfile profile : profilesFromConfig) {
            masterProfileMap.put(FrpProfileKey.from(profile), profile);
        }

        Set<String> openFirewallPorts = new HashSet<>();
        if (sections.length > 3) {
            String firewallSection = sections[3].replace("---FIREWALL_RULES---", "").trim();
            openFirewallPorts = parseFirewallRules(firewallSection);
        }

        for (FrpProfile profile : masterProfileMap.values()) {
            int port = profile.getRemotePort();
            String protocol = normalizeProtocol(profile.getProtocol());
            String serviceName = String.format(Locale.US, "frpc@%d_%s.service", port, protocol);
            String legacyServiceName = String.format(Locale.US, "frpc@%d.service", port);

            if (runningServices.contains(serviceName) || runningServices.contains(legacyServiceName)) {
                profile.setStatus("运行中");
            } else if (enabledStatuses.contains(serviceName + "\tenabled")
                || enabledStatuses.contains(legacyServiceName + "\tenabled")) {
                profile.setStatus("已停止");
            } else if (enabledStatuses.contains(serviceName + "\tdisabled")
                || enabledStatuses.contains(legacyServiceName + "\tdisabled")) {
                profile.setStatus("已禁用");
            } else {
                profile.setStatus("已停止");
            }

            String key = FrpProfileKey.from(profile);
            profile.setSelected(selectionState.getOrDefault(key, false));
            profile.setModified(false);
            profile.setFirewallStatus(openFirewallPorts.contains(key) ? "已放行" : "未放行");
        }

        for (FrpProfile pendingProfile : pendingProfiles) {
            pendingProfile.setModified(true);
            masterProfileMap.put(FrpProfileKey.from(pendingProfile), pendingProfile);
        }

        Map<String, FrpProfile> oldProfilesMap = oldProfiles.stream()
            .collect(Collectors.toMap(FrpProfileKey::from, profile -> profile, (left, right) -> right));

        for (String lockedKey : lockedProfiles) {
            if (masterProfileMap.containsKey(lockedKey) && oldProfilesMap.containsKey(lockedKey)) {
                masterProfileMap.get(lockedKey).setStatus(oldProfilesMap.get(lockedKey).getStatus());
            }
        }

        return new ArrayList<>(masterProfileMap.values());
    }

    public BatchOutputParseResult parseBatchOperationOutput(String output) {
        List<BatchOperationBlock> blocks = new ArrayList<>();
        Map<String, PortExecutionStatus> statusByProfileKey = new LinkedHashMap<>();

        if (output == null || output.trim().isEmpty()) {
            return new BatchOutputParseResult(blocks, statusByProfileKey);
        }

        if (!output.contains("======== PROCESSING PORT")) {
            blocks.add(new BatchOperationBlock(null, output, PortExecutionStatus.UNKNOWN));
            return new BatchOutputParseResult(blocks, statusByProfileKey);
        }

        Pattern headerPattern = Pattern.compile("PROCESSING PORT\\s+(\\d+)/([A-Z]+)");
        String[] rawBlocks = output.split("======== PROCESSING PORT");
        for (String rawBlock : rawBlocks) {
            if (rawBlock.trim().isEmpty()) {
                continue;
            }

            String fullBlock = "======== PROCESSING PORT" + rawBlock;
            PortExecutionStatus status = resolveExecutionStatus(rawBlock);
            String profileKey = null;

            Matcher matcher = headerPattern.matcher(fullBlock);
            if (matcher.find()) {
                int port = Integer.parseInt(matcher.group(1));
                String protocol = matcher.group(2).toLowerCase(Locale.US);
                profileKey = FrpProfileKey.from(port, protocol);
                statusByProfileKey.put(profileKey, status);
            }

            blocks.add(new BatchOperationBlock(profileKey, fullBlock, status));
        }

        return new BatchOutputParseResult(blocks, statusByProfileKey);
    }

    public List<String> parseFailedProfileKeys(String verifyOutput) {
        List<String> failedProfileKeys = new ArrayList<>();
        if (verifyOutput == null || verifyOutput.trim().isEmpty()) {
            return failedProfileKeys;
        }

        for (String line : verifyOutput.split("\n")) {
            if (line.startsWith("FAIL:")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    failedProfileKeys.add(parts[1]);
                }
            }
        }
        return failedProfileKeys;
    }

    private PortExecutionStatus resolveExecutionStatus(String block) {
        if (block.contains("FRP_MANAGER_STATUS:ERROR")) {
            return PortExecutionStatus.ERROR;
        }
        if (block.contains("FRP_MANAGER_STATUS:WARNING")) {
            return PortExecutionStatus.WARNING;
        }
        if (block.contains("FRP_MANAGER_STATUS:SUCCESS")) {
            return PortExecutionStatus.SUCCESS;
        }
        return PortExecutionStatus.UNKNOWN;
    }

    private Set<String> parseFirewallRules(String firewallOutput) {
        Set<String> openPorts = new HashSet<>();
        String[] parts = firewallOutput.split("---FW_SEP---");
        if (parts.length < 2) {
            Log.e(TAG, "Firewall output missing FW_SEP.");
            return openPorts;
        }

        String firewallType = parts[0].trim();
        String rulesContent = parts[1].trim();

        switch (firewallType) {
            case "firewalld":
                Matcher firewalldMatcher = Pattern.compile("(\\d+/(?:tcp|udp))").matcher(rulesContent);
                while (firewalldMatcher.find()) {
                    openPorts.add(firewalldMatcher.group(1));
                }
                break;
            case "ufw":
                Pattern ufwPattern = Pattern.compile("(\\d+)(?:,\\d+)*\\/(tcp|udp)");
                for (String line : rulesContent.split("\n")) {
                    Matcher ufwMatcher = ufwPattern.matcher(line);
                    while (ufwMatcher.find()) {
                        String portStr = ufwMatcher.group(1);
                        String protocol = ufwMatcher.group(2);
                        if (portStr.contains(",")) {
                            for (String port : portStr.split(",")) {
                                openPorts.add(port.trim() + "/" + protocol);
                            }
                        } else {
                            openPorts.add(portStr + "/" + protocol);
                        }
                    }
                }
                break;
            case "iptables":
                Pattern portPattern = Pattern.compile("--dport (\\d+)");
                Pattern protocolPattern = Pattern.compile("-p (tcp|udp)");
                for (String line : rulesContent.split("\n")) {
                    if (!line.contains("-j ACCEPT")) {
                        continue;
                    }
                    Matcher iptablesPortMatcher = portPattern.matcher(line);
                    if (iptablesPortMatcher.find()) {
                        String port = iptablesPortMatcher.group(1);
                        Matcher iptablesProtocolMatcher = protocolPattern.matcher(line);
                        String protocol = "tcp";
                        if (iptablesProtocolMatcher.find()) {
                            protocol = iptablesProtocolMatcher.group(1);
                        }
                        openPorts.add(port + "/" + protocol);
                    }
                }
                break;
            case "none":
            default:
                break;
        }

        return openPorts;
    }

    private List<FrpProfile> parseProfilesFromConfigSection(String configSection) {
        List<FrpProfile> profiles = new ArrayList<>();
        String cleanConfigSection = configSection.replace("---CONFIGS---", "").trim();
        String[] fileParts = cleanConfigSection.split("---FILE_END---");
        Pattern fileNamePattern = Pattern.compile("port_(\\d+)(?:_(\\w+))?\\.ini$");

        for (String part : fileParts) {
            String trimmedPart = part.trim();
            if (trimmedPart.isEmpty()) {
                continue;
            }

            String[] lines = trimmedPart.split("\n", 2);
            if (lines.length < 2) {
                Log.w(TAG, "Skipping invalid config part (not enough lines): " + trimmedPart);
                continue;
            }

            String filePath = lines[0].trim();
            if (filePath.endsWith(".ini.disabled")) {
                Log.d(TAG, "Skipping disabled config file: " + filePath);
                continue;
            }

            Matcher matcher = fileNamePattern.matcher(filePath);
            if (!matcher.find()) {
                Log.w(TAG, "Filename does not match pattern: " + filePath);
                continue;
            }

            String content = lines[1];
            if (content.trim().isEmpty()) {
                Log.w(TAG, "Skipping empty config file: " + filePath);
                continue;
            }

            FrpProfile profile = parseFrpProfileFromContent(content);
            if (profile == null) {
                Log.e(TAG, "Failed to parse content for file: " + filePath);
                continue;
            }

            profile.setRemotePort(Integer.parseInt(matcher.group(1)));
            String protocolFromFile = matcher.group(2);
            if (protocolFromFile != null && !protocolFromFile.isEmpty()) {
                profile.setProtocol(protocolFromFile.toLowerCase(Locale.US));
            } else {
                String protocolFromContent = parseValue(content, "type");
                profile.setProtocol(protocolFromContent.isEmpty() ? "tcp" : protocolFromContent.toLowerCase(Locale.US));
            }

            profiles.add(profile);
        }
        return profiles;
    }

    private FrpProfile parseFrpProfileFromContent(String content) {
        try {
            FrpProfile profile = new FrpProfile();
            String serverAddr = parseValue(content, "server_addr");
            if (!serverAddr.isEmpty()) {
                profile.setServerAddr(serverAddr);
            }

            String serverPort = parseValue(content, "server_port");
            if (!serverPort.isEmpty()) {
                profile.setServerPort(Integer.parseInt(serverPort));
            }

            String token = parseValue(content, "token");
            if (!token.isEmpty()) {
                profile.setToken(token);
            }

            String remotePort = parseValue(content, "remote_port");
            if (!remotePort.isEmpty()) {
                profile.setRemotePort(Integer.parseInt(remotePort));
            }

            profile.setLocalIp(parseValue(content, "local_ip"));

            String localPort = parseValue(content, "local_port");
            if (!localPort.isEmpty()) {
                profile.setLocalPort(Integer.parseInt(localPort));
            }

            String protocol = parseValue(content, "type");
            if (!protocol.isEmpty()) {
                profile.setProtocol(protocol);
            }

            String tag = parseValue(content, "#\\s*tag");
            if (!tag.isEmpty()) {
                tag = tag.replaceFirst("=", "").trim();
            }
            profile.setTag(tag);

            String proxyVersion = parseValue(content, "proxy_protocol_version");
            if (!proxyVersion.isEmpty()) {
                profile.setProxyProtocolVersion(proxyVersion);
            }
            return profile;
        } catch (Exception exception) {
            Log.e(TAG, "Exception while parsing profile content: " + content, exception);
            return null;
        }
    }

    private String parseValue(String content, String key) {
        Matcher matcher = Pattern.compile("^\\s*" + key + "\\s*=\\s*(.*)", Pattern.MULTILINE).matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private String normalizeProtocol(String protocol) {
        return protocol == null ? "tcp" : protocol.toLowerCase(Locale.US);
    }

    public static class BatchOutputParseResult {

        private final List<BatchOperationBlock> blocks;
        private final Map<String, PortExecutionStatus> statusByProfileKey;

        public BatchOutputParseResult(List<BatchOperationBlock> blocks, Map<String, PortExecutionStatus> statusByProfileKey) {
            this.blocks = blocks;
            this.statusByProfileKey = statusByProfileKey;
        }

        public List<BatchOperationBlock> getBlocks() {
            return blocks;
        }

        public Map<String, PortExecutionStatus> getStatusByProfileKey() {
            return statusByProfileKey;
        }
    }
}
