package io.momu.frpmanager;

import java.util.Locale;
import java.util.Objects;

public class FrpProfile {
    public static final String FIREWALL_ALLOWED = "已放行";
    public static final String FIREWALL_NOT_ALLOWED = "未放行";
    public static final String FIREWALL_UNKNOWN = "状态未知";
    public static final String FIREWALL_PENDING = "待应用";

    private String name;
    private String serverAddr;
    private int serverPort;
    private String token;
    private int remotePort;
    private String localIp = "127.0.0.1";
    private int localPort;
    private String protocol = "tcp";
    private String tag = "";
    private String proxyProtocolVersion;
    private String status = "未知";
    private boolean isSelected = false;
    private boolean isModified = false;
    private String firewallStatus;
    // Immutable remote resource identity discovered during loading.  Never infer it from display text.
    private String sourcePath;
    private String unitName;
    private String rawConfigContent;
    private int persistedRemotePort;
    private String persistedProtocol;
    private boolean legacy;
    private boolean configFileDisabled;
    private String proxyRegistrationState = "未知";
    private String lastError;
    public FrpProfile() {
    }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getServerAddr() { return serverAddr; }
    public void setServerAddr(String serverAddr) { this.serverAddr = serverAddr; }
    public int getServerPort() { return serverPort; }
    public void setServerPort(int serverPort) { this.serverPort = serverPort; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public int getRemotePort() { return remotePort; }
    public void setRemotePort(int remotePort) { this.remotePort = remotePort; }
    public String getLocalIp() { return localIp; }
    public void setLocalIp(String localIp) { this.localIp = localIp; }
    public int getLocalPort() { return localPort; }
    public void setLocalPort(int localPort) { this.localPort = localPort; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getProxyProtocolVersion() { return proxyProtocolVersion; }
    public void setProxyProtocolVersion(String version) { this.proxyProtocolVersion = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isSelected() { return isSelected; }
    public void setSelected(boolean selected) { isSelected = selected; }
    public boolean isModified() { return isModified; }
    public void setModified(boolean modified) { this.isModified = modified; }
    public String getFirewallStatus() { return firewallStatus; }
    public void setFirewallStatus(String firewallStatus) { this.firewallStatus = firewallStatus; }
    public boolean isFirewallAllowed() { return FIREWALL_ALLOWED.equals(firewallStatus); }
    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }
    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }
    public String getRawConfigContent() { return rawConfigContent; }
    public void setRawConfigContent(String rawConfigContent) { this.rawConfigContent = rawConfigContent; }
    public int getPersistedRemotePort() { return persistedRemotePort; }
    public void setPersistedRemotePort(int persistedRemotePort) { this.persistedRemotePort = persistedRemotePort; }
    public String getPersistedProtocol() { return persistedProtocol; }
    public void setPersistedProtocol(String persistedProtocol) { this.persistedProtocol = persistedProtocol; }
    public boolean isLegacy() { return legacy; }
    public void setLegacy(boolean legacy) { this.legacy = legacy; }
    public boolean isConfigFileDisabled() { return configFileDisabled; }
    public void setConfigFileDisabled(boolean disabled) { this.configFileDisabled = disabled; }
    public boolean isPersisted() { return sourcePath != null && !sourcePath.trim().isEmpty(); }
    public boolean hasPersistedIdentity() {
        return isPersisted() && persistedRemotePort > 0 && persistedProtocol != null && !persistedProtocol.trim().isEmpty();
    }
    public boolean hasPersistedIdentityChange() {
        return hasPersistedIdentity() && (persistedRemotePort != remotePort
            || !persistedProtocol.equalsIgnoreCase(protocol));
    }
    public String getPersistedIdentityKey() {
        return hasPersistedIdentity() ? persistedRemotePort + "/" + persistedProtocol.toLowerCase(Locale.US) : "";
    }
    public String getProxyRegistrationState() { return proxyRegistrationState; }
    public void setProxyRegistrationState(String state) { this.proxyRegistrationState = state; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    /**
     * A managed frpc and frps run on the same SSH host. These combinations
     * would either target frps itself or send a proxy listener back to itself.
     */
    public static String localFrpsSafetyError(String serverAddr, int serverPort,
        String localIp, int localPort, int remotePort) {
        if (!isLoopbackAddress(serverAddr)) return null;
        if (serverPort > 0 && remotePort == serverPort) {
            return "远程端口不能使用受管 frps 控制端口 " + serverPort + "。";
        }
        if (localPort > 0 && remotePort > 0 && localPort == remotePort) {
            return "受管同机 frps 不允许本地端口与远程端口相同，否则会形成转发回环。"
                + "请保留本地端口 " + localPort + "，并把远程端口改为其他未占用端口。";
        }
        if (serverPort > 0 && localPort == serverPort) {
            return "本地目标不能使用受管 frps 控制端口 " + serverPort + "，以免暴露控制通道。";
        }
        return null;
    }

    public String getLocalFrpsSafetyError() {
        return localFrpsSafetyError(serverAddr, serverPort, localIp, localPort, remotePort);
    }

    public String getManagedLocalFrpsSafetyError() {
        return isLoopbackAddress(serverAddr) ? getLocalFrpsSafetyError() : null;
    }

    public static boolean isLoopbackAddress(String address) {
        if (address == null) return false;
        String normalized = address.trim().toLowerCase(Locale.US);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equals(normalized) || "localhost.".equals(normalized)
            || "::1".equals(normalized) || normalized.startsWith("127.");
    }

    public void copyRemoteIdentityFrom(FrpProfile other) {
        if (other == null) return;
        sourcePath = other.sourcePath;
        unitName = other.unitName;
        rawConfigContent = other.rawConfigContent;
        persistedRemotePort = other.persistedRemotePort > 0 ? other.persistedRemotePort : other.remotePort;
        persistedProtocol = other.persistedProtocol == null || other.persistedProtocol.trim().isEmpty()
            ? other.protocol : other.persistedProtocol;
        legacy = other.legacy;
        configFileDisabled = other.configFileDisabled;
    }
    public boolean hasFunctionalChanges(FrpProfile other) {
        if (other == null) return true;
        return this.remotePort != other.remotePort ||
            this.localPort != other.localPort ||
            !Objects.equals(this.serverAddr, other.serverAddr) ||
            this.serverPort != other.serverPort ||
            !Objects.equals(this.token, other.token) ||
            !Objects.equals(this.localIp, other.localIp) ||
            !Objects.equals(this.protocol, other.protocol) ||
            !Objects.equals(this.proxyProtocolVersion, other.proxyProtocolVersion);
    }
    public void updateDataFrom(FrpProfile other) {
        this.setServerAddr(other.getServerAddr());
        this.setServerPort(other.getServerPort());
        this.setToken(other.getToken());
        this.setLocalIp(other.getLocalIp());
        this.setLocalPort(other.getLocalPort());
        this.setProtocol(other.getProtocol());
        this.setTag(other.getTag());
        this.setProxyProtocolVersion(other.getProxyProtocolVersion());
        copyRemoteIdentityFrom(other);
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FrpProfile that = (FrpProfile) o;
        return Objects.equals(name, that.name);
    }
    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
