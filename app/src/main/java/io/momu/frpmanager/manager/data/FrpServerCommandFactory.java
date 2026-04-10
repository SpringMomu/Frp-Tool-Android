package io.momu.frpmanager.manager.data;

import io.momu.frpmanager.FirewallManager;
import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.SshSettingsManager;
import io.momu.frpmanager.manager.util.FrpProfileKey;
import java.util.List;
import java.util.Locale;

public class FrpServerCommandFactory {

    private static final String SEPARATOR = "---FRP_MANAGER_SEPARATOR---";
    private final SshSettingsManager settingsManager;

    public FrpServerCommandFactory(SshSettingsManager settingsManager) {
        this.settingsManager = settingsManager;
    }

    public String buildDetectFirewallCommand() {
        return "if systemctl is-active --quiet firewalld; then echo \"firewalld\"; "
            + "elif command -v ufw >/dev/null && ufw status | grep -q \"Status: active\"; then echo \"ufw\"; "
            + "elif command -v iptables >/dev/null; then echo \"iptables\"; "
            + "else echo \"none\"; fi";
    }

    public String buildLoadProfilesCommand() {
        StringBuilder commandBuilder = new StringBuilder();
        commandBuilder.append("echo '---CONFIGS---' && ")
            .append("find /etc/frp/conf.d/ -name 'port_*.ini' -type f -exec sh -c 'echo \"{}\"; sudo cat \"{}\"; echo \"---FILE_END---\"' \\;");

        commandBuilder.append(" && echo '").append(SEPARATOR).append("' && echo '---RUNNING_SERVICES---' && ")
            .append("systemctl list-units --type=service --state=running 'frpc@*.service' --no-pager | awk '{print $1}'; ");
        commandBuilder.append("echo '").append(SEPARATOR).append("' && echo '---ENABLED_STATUS---' && ")
            .append("systemctl list-unit-files 'frpc@*.service' --no-pager | awk '{print $1,$2}';");
        commandBuilder.append("echo '").append(SEPARATOR).append("' && echo '---FIREWALL_RULES---' && ");
        commandBuilder.append("FW_TYPE=$(if systemctl is-active --quiet firewalld; then echo 'firewalld'; ")
            .append("elif command -v ufw >/dev/null && ufw status | grep -q 'Status: active'; then echo 'ufw'; ")
            .append("elif command -v iptables >/dev/null; then echo 'iptables'; else echo 'none'; fi); ")
            .append("echo \"$FW_TYPE\"; echo '---FW_SEP---'; ")
            .append("case $FW_TYPE in ")
            .append("'firewalld') firewall-cmd --list-ports ;; ")
            .append("'ufw') ufw status verbose | grep -w 'ALLOW' ;; ")
            .append("'iptables') iptables -S INPUT | grep -- '-j ACCEPT' ;; ")
            .append("esac;");
        return commandBuilder.toString();
    }

    public String buildApplySingleProfileChangeCommand(
        FrpProfile profile,
        boolean restart,
        boolean manageFirewall,
        FirewallManager firewallManager
    ) {
        StringBuilder commandBuilder = new StringBuilder("set -e; ");
        String protocol = normalizeProtocol(profile.getProtocol());

        if (manageFirewall && firewallManager != null) {
            String removeTcpCmd = firewallManager.removePortRuleCommand(profile.getRemotePort(), "tcp")
                .replace(" || true", "") + " || true";
            String removeUdpCmd = firewallManager.removePortRuleCommand(profile.getRemotePort(), "udp")
                .replace(" || true", "") + " || true";
            String addCmd = firewallManager.addPortRuleCommand(profile.getRemotePort(), protocol);
            commandBuilder.append(removeTcpCmd).append("; ")
                .append(removeUdpCmd).append("; ")
                .append(addCmd).append("; ");
        }

        commandBuilder.append(String.format(
            Locale.US,
            "sudo rm -f /etc/frp/conf.d/port_%d_%s.ini.disabled; "
                + "printf '%%s\\n' '%s' | sudo tee /etc/frp/conf.d/port_%d_%s.ini > /dev/null",
            profile.getRemotePort(),
            protocol,
            buildFileContent(profile),
            profile.getRemotePort(),
            protocol
        ));

        if (restart) {
            String serviceNew = String.format(Locale.US, "frpc@%d_%s.service", profile.getRemotePort(), protocol);
            String serviceOld = String.format(Locale.US, "frpc@%d.service", profile.getRemotePort());
            String restartBlock = String.format(
                Locale.US,
                "; SERVICE_NAME_TO_USE=''; "
                    + "if [ -f '/etc/frp/conf.d/port_%d_%s.ini' ]; then SERVICE_NAME_TO_USE='%s'; "
                    + "elif [ -f '/etc/frp/conf.d/port_%d.ini' ]; then SERVICE_NAME_TO_USE='%s'; fi; "
                    + "if [ -n \"$SERVICE_NAME_TO_USE\" ]; then sudo systemctl restart \"$SERVICE_NAME_TO_USE\"; "
                    + "else echo 'RESTART FAILED: config file not found.'; fi",
                profile.getRemotePort(),
                protocol,
                serviceNew,
                profile.getRemotePort(),
                serviceOld
            );
            commandBuilder.append(restartBlock);
        }

        if (manageFirewall && firewallManager != null) {
            commandBuilder.append("; ").append(firewallManager.reloadFirewallCommand()).append(";");
        }

        return commandBuilder.toString();
    }

    public BatchCommandPlan buildBatchOperationCommand(
        String operationType,
        List<FrpProfile> targets,
        boolean manageFirewall,
        FirewallManager firewallManager
    ) {
        StringBuilder commandBuilder = new StringBuilder();
        StringBuilder summaryBuilder = new StringBuilder();
        boolean firewallActionTaken = false;

        summaryBuilder.append("--- 命令执行计划 ---\n");

        switch (operationType) {
            case "start":
            case "restart":
                String mode = "start".equals(operationType) ? "启动" : "重启";
                summaryBuilder.append("执行模式：").append(mode).append("\n");
                for (FrpProfile profile : targets) {
                    int port = profile.getRemotePort();
                    String protocol = normalizeProtocol(profile.getProtocol());
                    String displayProtocol = protocol.toUpperCase(Locale.US);

                    summaryBuilder.append(String.format(Locale.US, "\n● 端口 %d/%s (%s):\n", port, displayProtocol, mode));
                    if (manageFirewall && firewallManager != null && "start".equals(operationType)) {
                        summaryBuilder.append(" - 防火墙: 添加端口规则\n");
                        commandBuilder.append(firewallManager.addPortRuleCommand(port, protocol)).append("; ");
                        firewallActionTaken = true;
                    }
                    summaryBuilder.append(" - 操 作: 添加新规则\n");
                    summaryBuilder.append(" - 验 证: 检查服务状态->诊断日志\n");

                    String serviceNew = String.format(Locale.US, "frpc@%d_%s.service", port, protocol);
                    String serviceOld = String.format(Locale.US, "frpc@%d.service", port);
                    String configFileNew = String.format(Locale.US, "/etc/frp/conf.d/port_%d_%s.ini", port, protocol);
                    String configFileOld = String.format(Locale.US, "/etc/frp/conf.d/port_%d.ini", port);
                    String actionVerb = "start".equals(operationType) ? "enable --now" : "restart";

                    String scriptBlock = String.format(
                        Locale.US,
                        "echo; echo '======== PROCESSING PORT %d/%s (%s) ========';\n"
                            + "echo '--> Step 1: Decommissioning legacy rule (if it exists)...';\n"
                            + "sudo systemctl disable --now %s >/dev/null 2>&1 || true;\n"
                            + "if [ -f \"%s\" ]; then sudo mv -f \"%s\" \"%s.bak\"; echo ' - Legacy config %s renamed to .bak'; fi;\n"
                            + "echo '--> Step 2: Writing new configuration to %s...';\n"
                            + "printf '%%s' '%s' | sudo tee \"%s\" > /dev/null;\n"
                            + "echo '--> Step 3: Performing action: %s';\n"
                            + "sudo systemctl %s %s; sleep 1.5;\n"
                            + "echo '--> Step 4: Verification & Diagnosis:';\n"
                            + "if sudo systemctl is-active --quiet \"%s\"; then\n"
                            + "  LOG_OUTPUT=$(sudo journalctl -u \"%s\" --no-pager -n 10);\n"
                            + "  if echo \"$LOG_OUTPUT\" | grep -q -E \"listener failed|start error|bind: address already in use\"; then echo 'FRP_MANAGER_STATUS:WARNING'; else echo 'FRP_MANAGER_STATUS:SUCCESS'; fi;\n"
                            + "  echo \"$LOG_OUTPUT\";\n"
                            + "else\n"
                            + "  echo 'FRP_MANAGER_STATUS:ERROR'; echo 'FRP_MANAGER_MESSAGE:服务启动失败！';\n"
                            + "  sudo journalctl -u \"%s\" --no-pager -n 20 || echo 'Could not retrieve logs.';\n"
                            + "fi\n",
                        port,
                        displayProtocol,
                        operationType,
                        serviceOld,
                        configFileOld,
                        configFileOld,
                        configFileOld,
                        configFileOld,
                        configFileNew,
                        buildFileContent(profile),
                        configFileNew,
                        actionVerb,
                        actionVerb,
                        serviceNew,
                        serviceNew,
                        serviceNew,
                        serviceNew
                    );
                    commandBuilder.append(scriptBlock);
                }
                break;
            case "stop":
                summaryBuilder.append("执行模式：暂停\n");
                for (FrpProfile profile : targets) {
                    int port = profile.getRemotePort();
                    String protocol = normalizeProtocol(profile.getProtocol());
                    String displayProtocol = protocol.toUpperCase(Locale.US);
                    summaryBuilder.append(String.format(Locale.US, "\n● 端口 %d/%s (暂停):\n", port, displayProtocol));
                    if (manageFirewall && firewallManager != null) {
                        commandBuilder.append(firewallManager.removePortRuleCommand(port, protocol)).append("; ");
                        firewallActionTaken = true;
                    }
                    summaryBuilder.append(" - 操 作: 停止服务\n");
                    String serviceNew = String.format(Locale.US, "frpc@%d_%s.service", port, protocol);
                    String serviceOld = String.format(Locale.US, "frpc@%d.service", port);
                    commandBuilder.append(String.format(
                        Locale.US,
                        "echo; echo '--- Processing Port %d/%s ---'; "
                            + "sudo systemctl disable --now %s >/dev/null 2>&1 || true; "
                            + "sudo systemctl disable --now %s >/dev/null 2>&1 || true;",
                        port,
                        displayProtocol,
                        serviceNew,
                        serviceOld
                    ));
                }
                break;
            case "delete":
            default:
                summaryBuilder.append("执行模式：删除\n");
                for (FrpProfile profile : targets) {
                    int port = profile.getRemotePort();
                    String protocol = normalizeProtocol(profile.getProtocol());
                    String displayProtocol = protocol.toUpperCase(Locale.US);
                    summaryBuilder.append(String.format(Locale.US, "\n● 端口 %d/%s (删除):\n", port, displayProtocol));
                    if (manageFirewall && firewallManager != null) {
                        commandBuilder.append(firewallManager.removePortRuleCommand(port, protocol)).append("; ");
                        firewallActionTaken = true;
                    }
                    summaryBuilder.append(" - 操 作: 精确清理服务和配置文件\n");

                    String serviceToDelete = String.format(Locale.US, "frpc@%d_%s.service", port, protocol);
                    String configToDelete = String.format(Locale.US, "/etc/frp/conf.d/port_%d_%s.ini", port, protocol);
                    commandBuilder.append(String.format(
                        Locale.US,
                        "echo; echo '--- Processing Port %d/%s for precise deletion ---';\n"
                            + "sudo systemctl disable --now %s >/dev/null 2>&1 || true;\n"
                            + "sudo rm -f /etc/systemd/system/%s;\n"
                            + "sudo rm -f %s;\n"
                            + "sudo rm -f %s.bak;\n"
                            + "echo 'Precise cleanup for port %d/%s completed.';\n",
                        port,
                        displayProtocol,
                        serviceToDelete,
                        serviceToDelete,
                        configToDelete,
                        configToDelete,
                        port,
                        displayProtocol
                    ));
                }
                commandBuilder.append("echo 'Reloading systemd...';\n")
                    .append("sudo systemctl daemon-reload;\n");
                break;
        }

        if (manageFirewall && firewallManager != null && firewallActionTaken) {
            summaryBuilder.append("\n● 防火墙:\n - 重载防火墙\n");
            commandBuilder.append("echo 'Reloading firewall rules...';\n")
                .append(firewallManager.reloadFirewallCommand()).append(";");
        }

        return new BatchCommandPlan(summaryBuilder.toString(), commandBuilder.toString());
    }

    public String buildApplyChangesCommand(
        List<FrpProfile> profilesToApply,
        boolean manageFirewall,
        FirewallManager firewallManager
    ) {
        StringBuilder commandBuilder = new StringBuilder("set -e; ");
        boolean firewallActionTaken = false;

        for (FrpProfile profile : profilesToApply) {
            String protocol = normalizeProtocol(profile.getProtocol());

            if (manageFirewall && firewallManager != null) {
                String removeTcpCmd = firewallManager.removePortRuleCommand(profile.getRemotePort(), "tcp")
                    .replace(" || true", "") + " || true";
                String removeUdpCmd = firewallManager.removePortRuleCommand(profile.getRemotePort(), "udp")
                    .replace(" || true", "") + " || true";
                String addCmd = firewallManager.addPortRuleCommand(profile.getRemotePort(), protocol);
                commandBuilder.append(removeTcpCmd).append("; ")
                    .append(removeUdpCmd).append("; ")
                    .append(addCmd).append("; ");
                firewallActionTaken = true;
            }

            commandBuilder.append(String.format(
                Locale.US,
                "sudo rm -f /etc/frp/conf.d/port_%d_%s.ini.disabled; "
                    + "printf '%%s\\n' '%s' | sudo tee /etc/frp/conf.d/port_%d_%s.ini > /dev/null; ",
                profile.getRemotePort(),
                protocol,
                buildFileContent(profile),
                profile.getRemotePort(),
                protocol
            ));
        }

        if (firewallActionTaken && firewallManager != null) {
            commandBuilder.append(firewallManager.reloadFirewallCommand()).append(";");
        }
        return commandBuilder.toString();
    }

    public String buildVerifyConfigFilesCommand(List<FrpProfile> profilesToApply) {
        StringBuilder verifyCommandBuilder = new StringBuilder();
        for (FrpProfile profile : profilesToApply) {
            String protocol = normalizeProtocol(profile.getProtocol());
            String key = FrpProfileKey.from(profile);
            verifyCommandBuilder.append(String.format(
                Locale.US,
                "if [ -f /etc/frp/conf.d/port_%d_%s.ini ]; then echo 'OK:%s'; else echo 'FAIL:%s'; fi; ",
                profile.getRemotePort(),
                protocol,
                key,
                key
            ));
        }
        return verifyCommandBuilder.toString();
    }

    public String buildSingleFirewallCommand(FrpProfile profile, String operation, FirewallManager firewallManager) {
        StringBuilder commandBuilder = new StringBuilder("set -e; ");
        if ("allow".equals(operation)) {
            commandBuilder.append(firewallManager.addPortRuleCommand(profile.getRemotePort(), normalizeProtocol(profile.getProtocol())));
        } else {
            commandBuilder.append(firewallManager.removePortRuleCommand(profile.getRemotePort(), normalizeProtocol(profile.getProtocol())));
        }
        commandBuilder.append("; ").append(firewallManager.reloadFirewallCommand()).append(";");
        return commandBuilder.toString();
    }

    public String buildBatchFirewallCommand(String operationType, List<FrpProfile> targets, FirewallManager firewallManager) {
        StringBuilder commandBuilder = new StringBuilder("set -e; ");
        for (FrpProfile profile : targets) {
            if ("allow".equals(operationType)) {
                commandBuilder.append(firewallManager.addPortRuleCommand(profile.getRemotePort(), normalizeProtocol(profile.getProtocol())));
            } else {
                commandBuilder.append(firewallManager.removePortRuleCommand(profile.getRemotePort(), normalizeProtocol(profile.getProtocol())));
            }
            commandBuilder.append("; ");
        }
        commandBuilder.append(firewallManager.reloadFirewallCommand()).append(";");
        return commandBuilder.toString();
    }

    public String buildReadConfigFileCommand(FrpProfile profile) {
        String protocol = normalizeProtocol(profile.getProtocol());
        return String.format(
            Locale.US,
            "sudo cat /etc/frp/conf.d/port_%d_%s.ini 2>/dev/null || "
                + "sudo cat /etc/frp/conf.d/port_%d.ini 2>/dev/null || "
                + "sudo cat /etc/frp/conf.d/port_%d_%s.ini.disabled 2>/dev/null || "
                + "sudo cat /etc/frp/conf.d/port_%d.ini.disabled 2>/dev/null || "
                + "echo '错误：在服务器上未找到对应的配置文件。'",
            profile.getRemotePort(),
            protocol,
            profile.getRemotePort(),
            profile.getRemotePort(),
            protocol,
            profile.getRemotePort()
        );
    }

    public String buildFileContent(FrpProfile profile) {
        StringBuilder commonBuilder = new StringBuilder();
        commonBuilder.append("[common]\n");

        String serverAddr = profile.getServerAddr() != null
            ? profile.getServerAddr()
            : settingsManager.getFrpServerAddr();
        int serverPort = profile.getServerPort() > 0 ? profile.getServerPort() : settingsManager.getFrpServerPort();
        String token = profile.getToken() != null ? profile.getToken() : settingsManager.getFrpToken();

        commonBuilder.append("server_addr = ").append(serverAddr).append("\n");
        commonBuilder.append("server_port = ").append(serverPort).append("\n");
        if (token != null && !token.isEmpty()) {
            commonBuilder.append("token = ").append(token).append("\n");
        }

        StringBuilder fileContentBuilder = new StringBuilder();
        fileContentBuilder.append(commonBuilder).append("\n\n");
        fileContentBuilder.append(String.format("# tag = %s\n", profile.getTag() != null ? profile.getTag() : ""));
        String sectionName = String.format(Locale.US, "%s_port_%d", normalizeProtocol(profile.getProtocol()), profile.getRemotePort());
        fileContentBuilder.append(String.format("[%s]\n", sectionName));
        fileContentBuilder.append(String.format("type = %s\n", normalizeProtocol(profile.getProtocol())));
        fileContentBuilder.append(String.format("local_ip = %s\n", profile.getLocalIp()));
        fileContentBuilder.append(String.format("local_port = %d\n", profile.getLocalPort()));
        fileContentBuilder.append(String.format("remote_port = %d", profile.getRemotePort()));
        if (profile.getProxyProtocolVersion() != null && !profile.getProxyProtocolVersion().isEmpty()) {
            fileContentBuilder.append(String.format("\nproxy_protocol_version = %s", profile.getProxyProtocolVersion()));
        }
        return escapeForSingleQuotedShell(fileContentBuilder.toString());
    }

    private String normalizeProtocol(String protocol) {
        return protocol == null ? "tcp" : protocol.toLowerCase(Locale.US);
    }

    private String escapeForSingleQuotedShell(String value) {
        return value.replace("'", "'\\''");
    }

    public static class BatchCommandPlan {

        private final String summary;
        private final String command;

        public BatchCommandPlan(String summary, String command) {
            this.summary = summary;
            this.command = command;
        }

        public String getSummary() {
            return summary;
        }

        public String getCommand() {
            return command;
        }
    }
}
