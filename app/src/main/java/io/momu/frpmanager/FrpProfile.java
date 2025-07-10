package io.momu.frpmanager;

import java.util.Objects;

public class FrpProfile {
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
