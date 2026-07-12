package io.momu.frpmanager.manager.data;

import io.momu.frpmanager.FirewallManager;
import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.SshSettingsManager;
import io.momu.frpmanager.manager.util.FrpProfileKey;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds fail-closed remote scripts. Commands rely on the app's required root SSH session. */
public class FrpServerCommandFactory {

    private final SshSettingsManager settingsManager;
    /**
     * Captured when the repository is created so a stale profile cannot switch a
     * managed deployment back to an old external endpoint during a start.
     */
    private final boolean embeddedFrpsManaged;

    public FrpServerCommandFactory(SshSettingsManager settingsManager) {
        this(settingsManager, settingsManager != null && settingsManager.isEmbeddedFrpsManaged());
    }

    FrpServerCommandFactory(SshSettingsManager settingsManager, boolean embeddedFrpsManaged) {
        this.settingsManager = settingsManager;
        this.embeddedFrpsManaged = embeddedFrpsManaged;
    }

    public String buildDetectFirewallCommand() {
        return "if systemctl is-active --quiet firewalld; then "
            + "ZONE=$(firewall-cmd --get-active-zones | awk 'NR==1 {print $1}'); "
            + "[ -n \"$ZONE\" ] || { echo none; exit 0; }; echo firewalld:$ZONE; "
            + "elif command -v ufw >/dev/null 2>&1 && ufw status | grep -q 'Status: active'; then echo ufw; "
            + "elif command -v iptables >/dev/null 2>&1 && (iptables -S INPUT | grep -q -- '-j \\(DROP\\|REJECT\\)' "
            + "|| command -v netfilter-persistent >/dev/null 2>&1 || [ -d /etc/sysconfig ]); then echo iptables; "
            + "else echo none; fi";
    }

    /**
     * Emits a line protocol with base64 path/content so an INI value cannot corrupt the loader protocol.
     */
    public String buildLoadProfilesCommand() {
        return "set -eu; "
            + "if [ -d /etc/frp/conf.d ]; then find /etc/frp/conf.d -type f \\( -name 'port_*.ini' "
            + "-o -name 'port_*.ini.disabled' \\) -print | sort | "
            + "while IFS= read -r f; do "
            + "printf 'FRP_FILE\\t'; printf '%s' \"$f\" | base64 | tr -d '\\n'; printf '\\t'; "
            + "base64 < \"$f\" | tr -d '\\n'; printf '\\n'; done; fi; "
            + "systemctl list-units --type=service --state=running 'frpc@*.service' --no-legend --no-pager "
            + "| awk '{print \"FRP_RUNNING\\t\" $1}'; "
            + "systemctl list-unit-files 'frpc@*.service' --no-legend --no-pager "
            + "| awk '{print \"FRP_UNIT\\t\" $1 \"\\t\" $2}'";
    }

    public String buildReadFirewallRulesCommand() {
        return "set -eu; "
            + "FW_TYPE=$(" + buildDetectFirewallCommand() + "); echo \"FRP_FIREWALL_TYPE\\t$FW_TYPE\"; "
            + "case $FW_TYPE in "
            + "firewalld:*) firewall-cmd --permanent --direct --get-all-chains; "
            + "firewall-cmd --permanent --direct --get-all-rules ipv4 filter; "
            + "firewall-cmd --permanent --direct --get-all-rules ipv6 filter ;; "
            + "ufw) ufw status numbered ;; "
            + "iptables) iptables -S FRP_TOOL 2>/dev/null || true; ip6tables -S FRP_TOOL 2>/dev/null || true ;; "
            + "esac";
    }

    /** Writes one config atomically; the activity coordinates any separately hosted firewall rule. */
    public String buildApplySingleProfileChangeCommand(FrpProfile profile, boolean restart,
        boolean manageFirewall, FirewallManager firewallManager) {
        // restart/manageFirewall are retained only for source compatibility. File writes are isolated from service control.
        return buildAtomicApplyBlock(profile);
    }

    public String buildApplyChangesCommand(List<FrpProfile> profilesToApply, boolean manageFirewall,
        FirewallManager firewallManager) {
        StringBuilder command = new StringBuilder("set +e; failures=0; ");
        for (FrpProfile profile : profilesToApply) {
            command.append("echo '======== PROCESSING PORT ")
                .append(profile.getRemotePort()).append('/').append(normalizeProtocol(profile.getProtocol()).toUpperCase(Locale.US))
                .append(" (apply) ========'; ");
            command.append(buildAtomicApplyBlock(profile));
            command.append(" || { echo 'APPLY_FAIL:").append(FrpProfileKey.from(profile))
                .append("' >&2; failures=1; }; ");
        }
        command.append("exit $failures");
        return command.toString();
    }

    public String buildVerifyConfigFilesCommand(List<FrpProfile> profilesToApply) {
        StringBuilder command = new StringBuilder("set +e; failures=0; ");
        for (FrpProfile profile : profilesToApply) {
            String key = FrpProfileKey.from(profile);
            String file = targetPathFor(profile);
            command.append("if [ -f '").append(file).append("' ] && /usr/local/bin/frpc verify -c '")
                .append(file).append("' >/dev/null 2>&1; then echo 'OK:").append(key)
                .append("'; else echo 'FAIL:").append(key).append("'; failures=1; fi; ");
        }
        return command.append("exit $failures").toString();
    }

    public BatchCommandPlan buildBatchOperationCommand(String operationType, List<FrpProfile> targets,
        boolean manageFirewall, FirewallManager firewallManager) {
        StringBuilder command = new StringBuilder("set +e; failures=0; ");
        StringBuilder summary = new StringBuilder("--- 命令执行计划 ---\n执行模式：")
            .append(operationName(operationType)).append("\n");
        for (FrpProfile profile : targets) {
            String key = FrpProfileKey.from(profile);
            String unit = unitNameFor(profile);
            String path = sourcePathFor(profile);
			String activePath = path.endsWith(".disabled") ? path.substring(0, path.length() - ".disabled".length()) : path;
            String label = profile.getRemotePort() + "/" + normalizeProtocol(profile.getProtocol()).toUpperCase(Locale.US);
            summary.append("\n● 端口 ").append(label).append("\n");
            command.append("echo '======== PROCESSING PORT ").append(label).append(" (")
                .append(operationType).append(") ========'; ");
            if ("start".equals(operationType) || "restart".equals(operationType)) {
                String action = "start".equals(operationType) ? "enable --now" : "restart";
                boolean wasDisabled = path.endsWith(".disabled");
                String serverAddr = effectiveServerAddress(profile);
                int serverPort = effectiveServerPort(profile);
                String localFrpsSafetyError = localFrpsSafetyError(profile);
                String stopUnit = "systemctl disable --now " + shellQuote(unit)
                    + " >/dev/null 2>&1 || true; systemctl reset-failed " + shellQuote(unit)
                    + " >/dev/null 2>&1 || true; ";
                String restoreDisabled = wasDisabled
                    ? "if [ -f " + shellQuote(activePath) + " ]; then mv -f " + shellQuote(activePath) + " "
                        + shellQuote(path) + " >/dev/null 2>&1 || true; fi; "
                    : "";

                command.append("FRP_CAN_START=1; FRP_HOST=").append(shellQuote(serverAddr))
                    .append("; FRP_PORT=").append(serverPort).append("; ");
                if (localFrpsSafetyError != null) {
                    command.append(stopUnit).append(startFailure(key,
                        localFrpsSafetyReason(profile), localFrpsSafetyError))
                        .append(" FRP_CAN_START=0; ");
                }
                if (embeddedFrpsManaged && localFrpsSafetyError == null) {
                    // A profile may have been loaded before the setup wizard completed,
                    // or may still contain the old external endpoint. Repair it before
                    // verification and make the managed frps service self-healing.
                    command.append("if [ \"$FRP_CAN_START\" -eq 1 ]; then ")
                        .append("if [ ! -f '/etc/systemd/system/frps.service' ] || ! grep -Fqx '# Frp Tool embedded frps service v1' '/etc/systemd/system/frps.service' ")
                        .append("|| [ ! -f '/etc/frp/frps.toml' ] || ! grep -Fqx '# Frp Tool embedded frps v1' '/etc/frp/frps.toml'; then ")
                        .append(stopUnit).append(startFailure(key, "MANAGED_ENVIRONMENT_INVALID",
                            "受管 frps 环境不完整，无法自动修复；请重新执行一次内置 FRP 部署。"))
                        .append(" FRP_CAN_START=0; ")
                        .append("elif ! systemctl enable --now frps.service >/dev/null 2>&1 || ! systemctl is-active --quiet frps.service; then ")
                        .append(stopUnit).append(startFailure(key, "MANAGED_FRPS_START_FAILED",
                            "受管 frps.service 未能自动启动，请检查服务日志或重新执行内置 FRP 部署。"))
                        .append(" FRP_CAN_START=0; ")
                        .append("elif ! ").append(buildAtomicWriteBlock(profile, false)).append("; then ")
                        .append(stopUnit).append(startFailure(key, "CONFIG_REPAIR_FAILED",
                            "已检测到旧端点配置，但无法自动修复为 127.0.0.1:7000；服务未启动。"))
                        .append(" FRP_CAN_START=0; fi; fi; ");
                }
                command.append("if [ \"$FRP_CAN_START\" -eq 1 ]; then ")
                    .append("if [ ! -f ").append(shellQuote(path)).append(" ]; then ")
                    .append(stopUnit).append(startFailure(key, "CONFIG_INVALID", "配置文件不存在，服务未启动。"))
                    .append(" FRP_CAN_START=0; ")
                    .append("elif ! /usr/local/bin/frpc verify -c ").append(shellQuote(path)).append("; then ")
                    .append(stopUnit).append(startFailure(key, "CONFIG_INVALID", "FRP 配置校验失败，服务未启动。"))
                    .append(" FRP_CAN_START=0; ")
                    .append("elif [ -z \"$FRP_HOST\" ] || [ \"$FRP_PORT\" -lt 1 ] || [ \"$FRP_PORT\" -gt 65535 ]; then ")
                    .append(stopUnit).append(startFailure(key, "CONFIG_INVALID", "FRP 服务端地址或端口无效，服务未启动。"))
                    .append(" FRP_CAN_START=0; ")
                    .append("elif ! command -v timeout >/dev/null 2>&1 || ! command -v bash >/dev/null 2>&1; then ")
                    .append(stopUnit).append(startFailure(key, "ENDPOINT_CHECK_UNAVAILABLE",
                        "服务器缺少 bash 或 timeout，无法安全检查 frps 端点，已拒绝启动。"))
                    .append(" FRP_CAN_START=0; ")
                    .append("elif ! timeout 5 bash -c 'exec 3<>/dev/tcp/$1/$2' _ \"$FRP_HOST\" \"$FRP_PORT\" 2>/dev/null; then ")
                    .append(stopUnit).append(startFailure(key, "FRPS_UNREACHABLE",
                        "目标 frps 端点 " + serverAddr + ":" + serverPort
                            + " 不可达。托管模式请修复 frps.service；外部模式请检查地址、端口及运行状态。"))
                    .append(" FRP_CAN_START=0; fi; fi; ")
                    .append("if [ \"$FRP_CAN_START\" -eq 1 ]; then ");

                if (wasDisabled) {
                    command.append("if ! mv -f ").append(shellQuote(path)).append(' ')
                        .append(shellQuote(activePath)).append("; then ")
                        .append(stopUnit).append(startFailure(key, "CONFIG_INVALID", "无法启用配置文件，服务未启动。"))
                        .append(" FRP_CAN_START=0; fi; ");
                }

                command.append("if [ \"$FRP_CAN_START\" -eq 1 ]; then ")
                    .append("systemctl reset-failed ").append(shellQuote(unit)).append(" >/dev/null 2>&1 || true; ")
                    .append("FRP_START_EPOCH=$(date +%s); ")
                    .append("if ! systemctl ").append(action).append(' ').append(shellQuote(unit)).append("; then ")
                    .append("LOG=$(journalctl -u ").append(shellQuote(unit))
                    .append(" --since \"@$FRP_START_EPOCH\" --no-pager -n 120 2>&1); ")
                    .append(classifyStartFailure())
                    .append(stopUnit).append(restoreDisabled)
                    .append(startFailureFromVariables(key)).append(" echo \"$LOG\"; ")
                    .append("else sleep 2; LOG=$(journalctl -u ").append(shellQuote(unit))
                    .append(" --since \"@$FRP_START_EPOCH\" --no-pager -n 120 2>&1); ")
                    .append("if ! systemctl is-active --quiet ").append(shellQuote(unit))
                    .append(" || printf '%s\\n' \"$LOG\" | grep -Eqi ")
                    .append(shellQuote("connect to server error|login to (the )?server failed|authentication failed|token.*(incorrect|invalid|mismatch|failed|doesn.?t match|does not match|not match)|start error|listener failed|address already in use|port.*already.*used|proxy.*(error|failed)"))
                    .append("; then ").append(classifyStartFailure()).append(stopUnit).append(restoreDisabled)
                    .append(startFailureFromVariables(key)).append(" echo \"$LOG\"; ")
                    .append("elif printf '%s\\n' \"$LOG\" | grep -Eqi ")
                    .append(shellQuote("start proxy success|proxy.*success"))
                    .append("; then echo 'FRP_MANAGER_STATUS:SUCCESS'; echo 'FRP_MANAGER_KEY:").append(key)
                    .append("'; else echo 'FRP_MANAGER_STATUS:WARNING'; echo 'FRP_MANAGER_REASON:PROXY_REGISTRATION_UNCONFIRMED'; ")
                    .append("echo 'FRP_MANAGER_KEY:").append(key)
                    .append("'; echo 'frpc 进程正在运行，但未发现本次代理注册成功日志；不会自动开放防火墙。'; fi; fi; fi; fi; ");
            } else if ("stop".equals(operationType)) {
                command.append("if systemctl disable --now '").append(unit).append("'; then echo 'FRP_MANAGER_STATUS:SUCCESS'; ")
                    .append("echo 'FRP_MANAGER_KEY:").append(key).append("'; else echo 'FRP_MANAGER_STATUS:ERROR'; ")
                    .append("echo 'FRP_MANAGER_KEY:").append(key).append("'; failures=1; fi; ");
            } else { // delete
                command.append("systemctl disable --now ").append(shellQuote(unit)).append(" >/dev/null 2>&1 || true; ")
                    .append("systemctl reset-failed ").append(shellQuote(unit)).append(" >/dev/null 2>&1 || true; ")
                    .append("rm -f ").append(shellQuote(path)).append("; systemctl daemon-reload; ")
                    .append("if [ ! -e ").append(shellQuote(path)).append(" ] && ! systemctl is-active --quiet ").append(shellQuote(unit))
                    .append(" && ! systemctl is-enabled --quiet ").append(shellQuote(unit))
                    .append("; then echo 'FRP_MANAGER_STATUS:SUCCESS'; echo 'FRP_MANAGER_KEY:").append(key)
                    .append("'; else echo 'FRP_MANAGER_STATUS:ERROR'; echo 'FRP_MANAGER_REASON:DELETE_VERIFY_FAILED'; echo 'FRP_MANAGER_KEY:")
                    .append(key).append("'; failures=1; fi; ");
            }
        }
        command.append("exit $failures");
        return new BatchCommandPlan(summary.toString(), command.toString());
    }

    public String buildSingleFirewallCommand(FrpProfile profile, String operation, FirewallManager firewallManager) {
        int port = profile.getRemotePort();
        String protocol = normalizeProtocol(profile.getProtocol());
        boolean allow = "allow".equals(operation);
        String query = firewallManager.ruleExistsCommand(port, protocol);
        String add = firewallManager.addPortRuleCommand(port, protocol);
        String remove = firewallManager.removePortRuleCommand(port, protocol);
        String mutation = allow ? add : remove;
        String reload = firewallManager.reloadFirewallCommand();
        StringBuilder command = new StringBuilder("set -eu; ")
            .append(captureRuleState(query, "FRP_RULE_PRE"))
            .append("FRP_MUTATION_FAILED=0; if ! (").append(mutation)
            .append("); then FRP_MUTATION_FAILED=1; fi; ")
            .append(captureRuleState(query, "FRP_RULE_POST"))
            .append("if [ \"$FRP_MUTATION_FAILED\" -ne 0 ]; then ")
            .append(rollbackRuleIfChanged("FRP_RULE_PRE", "FRP_RULE_POST", add, remove))
            .append(reload).append(" >/dev/null 2>&1 || true; exit 1; fi; ")
            .append("if ! (").append(reload).append("); then ")
            .append(rollbackRuleIfChanged("FRP_RULE_PRE", "FRP_RULE_POST", add, remove))
            .append(reload).append(" >/dev/null 2>&1 || true; exit 1; fi; ");
        if (allow) {
            command.append("if ! (").append(query).append("); then ");
        } else {
            command.append("if (").append(query).append("); then ");
        }
        return command.append("echo '防火墙规则结果校验失败' >&2; ")
            .append(captureRuleState(query, "FRP_RULE_POST"))
            .append(rollbackRuleIfChanged("FRP_RULE_PRE", "FRP_RULE_POST", add, remove))
            .append(reload).append(" >/dev/null 2>&1 || true; exit 1; fi").toString();
    }

    public String buildBatchFirewallCommand(String operationType, List<FrpProfile> targets, FirewallManager firewallManager) {
        boolean allow = "allow".equals(operationType);
        StringBuilder command = new StringBuilder("set -eu; FRP_MUTATION_FAILED=0; ");
        StringBuilder rollback = new StringBuilder();
        StringBuilder verify = new StringBuilder("FRP_VERIFY_FAILED=0; ");
        for (int index = 0; index < targets.size(); index++) {
            FrpProfile profile = targets.get(index);
            int port = profile.getRemotePort();
            String protocol = normalizeProtocol(profile.getProtocol());
            String query = firewallManager.ruleExistsCommand(port, protocol);
            String add = firewallManager.addPortRuleCommand(port, protocol);
            String remove = firewallManager.removePortRuleCommand(port, protocol);
            String mutation = allow ? add : remove;
            String pre = "FRP_RULE_PRE_" + index;
            String post = "FRP_RULE_POST_" + index;
            command.append(captureRuleState(query, pre))
                .append("if ! (").append(mutation).append("); then FRP_MUTATION_FAILED=1; fi; ")
                .append(captureRuleState(query, post));
            rollback.append(rollbackRuleIfChanged(pre, post, add, remove));
            if (allow) {
                verify.append("if ! (").append(query).append("); then ");
            } else {
                verify.append("if (").append(query).append("); then ");
            }
            verify.append("echo 'FIREWALL_VERIFY_FAILED:").append(FrpProfileKey.from(profile))
                .append("' >&2; FRP_VERIFY_FAILED=1; fi; ");
        }
        command.append("if [ \"$FRP_MUTATION_FAILED\" -ne 0 ]; then ").append(rollback)
            .append(firewallManager.reloadFirewallCommand()).append(" >/dev/null 2>&1 || true; exit 1; fi; ")
            .append("if ! (").append(firewallManager.reloadFirewallCommand()).append("); then ")
            .append(rollback).append(firewallManager.reloadFirewallCommand())
            .append(" >/dev/null 2>&1 || true; exit 1; fi; ")
            .append(verify)
            .append("if [ \"$FRP_VERIFY_FAILED\" -ne 0 ]; then ");
        for (int index = 0; index < targets.size(); index++) {
            FrpProfile profile = targets.get(index);
            String query = firewallManager.ruleExistsCommand(profile.getRemotePort(), normalizeProtocol(profile.getProtocol()));
            command.append(captureRuleState(query, "FRP_RULE_POST_" + index));
        }
        return command.append(rollback).append(firewallManager.reloadFirewallCommand())
            .append(" >/dev/null 2>&1 || true; exit 1; fi").toString();
    }

    public String buildReadConfigFileCommand(FrpProfile profile) {
        return "cat '" + sourcePathFor(profile) + "'";
    }

    /** Generated only for new profiles. Existing content is patched in-place to preserve unknown fields/comments. */
    public String buildFileContent(FrpProfile profile) {
        String existing = profile.getRawConfigContent();
        if (existing != null && !existing.trim().isEmpty()) {
            return patchManagedFields(existing, profile);
        }
        validateProfileValues(profile);
        String serverAddr = effectiveServerAddress(profile);
        int serverPort = effectiveServerPort(profile);
        String token = effectiveToken(profile);
        String protocol = normalizeProtocol(profile.getProtocol());
        StringBuilder content = new StringBuilder("# Frp Tool managed\n[common]\n")
            .append("server_addr = ").append(serverAddr).append("\n")
            .append("server_port = ").append(serverPort).append("\n");
        if (token != null && !token.isEmpty()) content.append("token = ").append(token).append("\n");
        content.append("\n# tag = ").append(profile.getTag() == null ? "" : profile.getTag()).append("\n")
            .append("[").append(protocol).append("_port_").append(profile.getRemotePort()).append("]\n")
            .append("type = ").append(protocol).append("\n")
            .append("local_ip = ").append(profile.getLocalIp()).append("\n")
            .append("local_port = ").append(profile.getLocalPort()).append("\n")
            .append("remote_port = ").append(profile.getRemotePort()).append("\n");
        if (profile.getProxyProtocolVersion() != null && !profile.getProxyProtocolVersion().isEmpty()) {
            content.append("proxy_protocol_version = ").append(profile.getProxyProtocolVersion()).append("\n");
        }
        return content.toString();
    }

    private String buildAtomicApplyBlock(FrpProfile profile) {
        return profile.hasPersistedIdentityChange() ? buildAtomicMigrationBlock(profile)
            : buildAtomicWriteBlock(profile, false);
    }

    private String buildAtomicWriteBlock(FrpProfile profile, boolean failFast) {
        String key = FrpProfileKey.from(profile);
        String file = targetPathFor(profile);
        String content = buildFileContent(profile);
        String payload = java.util.Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hash = sha256(content);
        return "( set -e; target='" + file + "'; tmp=\"${target}.frp-tool.$$\"; backup=\"${target}.frp-tool.bak.$(date +%s)\"; "
            + "cleanup() { rm -f \"$tmp\"; }; trap cleanup EXIT; umask 077; "
            + "printf '%s' '" + payload + "' | base64 -d > \"$tmp\" && "
            + "[ \"$(sha256sum \"$tmp\" | awk '{print $1}')\" = '" + hash + "' ] && "
            + "/usr/local/bin/frpc verify -c \"$tmp\" >/dev/null && "
            + "{ [ ! -e \"$target\" ] || cp -p \"$target\" \"$backup\"; } && "
            + "mv -f \"$tmp\" \"$target\" && "
            + "[ \"$(sha256sum \"$target\" | awk '{print $1}')\" = '" + hash + "' ] && "
            + "{ trap - EXIT; echo 'APPLY_OK:" + key + "'; } )";
    }

    private String buildAtomicMigrationBlock(FrpProfile profile) {
        String key = FrpProfileKey.from(profile);
        String source = sourcePathFor(profile);
        String target = targetPathFor(profile);
        String oldUnit = unitNameFor(profile);
        String content = buildFileContent(profile);
        String payload = java.util.Base64.getEncoder().encodeToString(
            content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hash = sha256(content);
        return "( set -e; source=" + shellQuote(source) + "; target=" + shellQuote(target)
            + "; old_unit=" + shellQuote(oldUnit) + "; tmp=\"${target}.frp-tool.$$\"; "
            + "backup=\"${source}.frp-tool.bak.$(date +%s)\"; cleanup() { rm -f \"$tmp\"; }; trap cleanup EXIT; umask 077; "
            + "[ -f \"$source\" ] || { echo '原端口配置不存在，无法迁移。' >&2; exit 1; }; "
            + "[ \"$source\" = \"$target\" ] || [ ! -e \"$target\" ] || { echo '目标端口配置已存在，拒绝覆盖。' >&2; exit 1; }; "
            + "systemctl disable --now \"$old_unit\" >/dev/null 2>&1 || true; "
            + "! systemctl is-active --quiet \"$old_unit\" || { echo '旧端口服务未能停止，拒绝迁移。' >&2; exit 1; }; "
            + "cp -p \"$source\" \"$backup\"; "
            + "printf '%s' '" + payload + "' | base64 -d > \"$tmp\"; "
            + "[ \"$(sha256sum \"$tmp\" | awk '{print $1}')\" = '" + hash + "' ]; "
            + "/usr/local/bin/frpc verify -c \"$tmp\" >/dev/null; mv -f \"$tmp\" \"$target\"; "
            + "[ \"$(sha256sum \"$target\" | awk '{print $1}')\" = '" + hash + "' ]; "
            + "if [ \"$source\" != \"$target\" ]; then rm -f \"$source\"; fi; systemctl daemon-reload; "
            + "[ -f \"$target\" ]; [ \"$source\" = \"$target\" ] || [ ! -e \"$source\" ]; "
            + "trap - EXIT; echo 'APPLY_OK:" + key + "' )";
    }

    private String patchManagedFields(String existing, FrpProfile profile) {
        validateProfileValues(profile);
        String patched = existing;
        patched = replaceOrAdd(patched, "server_addr", effectiveServerAddress(profile), "[common]");
        patched = replaceOrAdd(patched, "server_port", String.valueOf(effectiveServerPort(profile)), "[common]");
        String token = effectiveToken(profile);
        patched = token == null || token.isEmpty() ? removeKey(patched, "token") : replaceOrAdd(patched, "token", token, "[common]");
        patched = replaceOrAdd(patched, "type", normalizeProtocol(profile.getProtocol()), null);
        patched = replaceOrAdd(patched, "local_ip", profile.getLocalIp(), null);
        patched = replaceOrAdd(patched, "local_port", String.valueOf(profile.getLocalPort()), null);
        patched = replaceOrAdd(patched, "remote_port", String.valueOf(profile.getRemotePort()), null);
        String proxy = profile.getProxyProtocolVersion();
        patched = proxy == null || proxy.isEmpty() ? removeKey(patched, "proxy_protocol_version")
            : replaceOrAdd(patched, "proxy_protocol_version", proxy, null);
        Pattern tag = Pattern.compile("(?m)^\\s*#\\s*tag\\s*=.*$");
        String tagLine = "# tag = " + (profile.getTag() == null ? "" : profile.getTag());
        return tag.matcher(patched).find() ? tag.matcher(patched).replaceFirst(Matcher.quoteReplacement(tagLine))
            : tagLine + "\n" + patched;
    }

    private String replaceOrAdd(String content, String key, String value, String section) {
        Pattern pattern = Pattern.compile("(?m)^(\\s*" + Pattern.quote(key) + "\\s*=).*$" );
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) return matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + " " + value));
        if (section != null) {
            Pattern sectionPattern = Pattern.compile("(?m)^" + Pattern.quote(section) + "\\s*$");
            Matcher sectionMatcher = sectionPattern.matcher(content);
            if (sectionMatcher.find()) return content.substring(0, sectionMatcher.end()) + "\n" + key + " = " + value + content.substring(sectionMatcher.end());
        }
        return content + (content.endsWith("\n") ? "" : "\n") + key + " = " + value + "\n";
    }

    private String removeKey(String content, String key) {
        return content.replaceAll("(?m)^\\s*" + Pattern.quote(key) + "\\s*=.*(?:\\r?\\n|$)", "");
    }

    private void validateProfileValues(FrpProfile profile) {
        requireSingleLine("server_addr", effectiveServerAddress(profile));
        requireSingleLine("token", effectiveToken(profile));
        requireSingleLine("local_ip", profile.getLocalIp());
        requireSingleLine("tag", profile.getTag());
        requireSingleLine("proxy_protocol_version", profile.getProxyProtocolVersion());
        String protocol = normalizeProtocol(profile.getProtocol());
        if (!("tcp".equals(protocol) || "udp".equals(protocol)) || profile.getRemotePort() < 1 || profile.getRemotePort() > 65535
            || profile.getLocalPort() < 1 || profile.getLocalPort() > 65535) {
            throw new IllegalArgumentException("端口或协议无效");
        }
        String localFrpsSafetyError = localFrpsSafetyError(profile);
        if (localFrpsSafetyError != null) throw new IllegalArgumentException(localFrpsSafetyError);
    }

    private void requireSingleLine(String name, String value) {
        if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\u0000') >= 0)) {
            throw new IllegalArgumentException(name + " 不能包含换行或控制字符");
        }
    }

    private String effectiveServerAddress(FrpProfile profile) {
        if (embeddedFrpsManaged) return "127.0.0.1";
        String address = profile.getServerAddr();
        if ((address == null || address.trim().isEmpty()) && settingsManager != null) {
            address = settingsManager.getFrpServerAddr();
        }
        return address == null ? "" : address.trim();
    }

    private int effectiveServerPort(FrpProfile profile) {
        if (embeddedFrpsManaged) return 7000;
        if (profile.getServerPort() > 0) return profile.getServerPort();
        return settingsManager == null ? 0 : settingsManager.getFrpServerPort();
    }

    private String effectiveToken(FrpProfile profile) {
        if (embeddedFrpsManaged && settingsManager != null) {
            String managedToken = settingsManager.getFrpToken();
            if (managedToken != null && !managedToken.trim().isEmpty()) return managedToken.trim();
        }
        if (profile.getToken() != null) return profile.getToken();
        return settingsManager == null ? "" : settingsManager.getFrpToken();
    }

    private String localFrpsSafetyError(FrpProfile profile) {
        return FrpProfile.localFrpsSafetyError(effectiveServerAddress(profile), effectiveServerPort(profile),
            profile.getLocalIp(), profile.getLocalPort(), profile.getRemotePort());
    }

    private String localFrpsSafetyReason(FrpProfile profile) {
        if (effectiveServerPort(profile) > 0 && profile.getRemotePort() == effectiveServerPort(profile)) {
            return "MANAGED_CONTROL_PORT_CONFLICT";
        }
        return "LOCAL_PORT_LOOP";
    }

    private String startFailure(String key, String reason, String message) {
        return "echo 'FRP_MANAGER_STATUS:ERROR'; echo 'FRP_MANAGER_REASON:" + reason
            + "'; echo 'FRP_MANAGER_KEY:" + key + "'; echo " + shellQuote(message) + "; failures=1;";
    }

    private String startFailureFromVariables(String key) {
        return "echo 'FRP_MANAGER_STATUS:ERROR'; echo \"FRP_MANAGER_REASON:$FRP_REASON\"; "
            + "echo 'FRP_MANAGER_KEY:" + key + "'; echo \"$FRP_MESSAGE\"; failures=1;";
    }

    private String classifyStartFailure() {
        return "FRP_REASON='SERVICE_START_FAILED'; FRP_MESSAGE='frpc 服务未能保持运行，请检查本次启动日志。'; "
            + "if printf '%s\\n' \"$LOG\" | grep -Eqi 'connection refused|connect to server error|no route to host|network is unreachable|i/o timeout|connection timed out'; then "
            + "FRP_REASON='FRPS_UNREACHABLE'; FRP_MESSAGE=\"目标 frps 端点 $FRP_HOST:$FRP_PORT 不可达。托管模式请修复 frps.service；外部模式请检查地址、端口及运行状态。\"; "
            + "elif printf '%s\\n' \"$LOG\" | grep -Eqi 'authentication failed|authorization failed|token.*(incorrect|invalid|mismatch|failed|doesn.?t match|does not match|not match)|unauthorized'; then "
            + "FRP_REASON='AUTHENTICATION_FAILED'; FRP_MESSAGE='frps 拒绝了登录，请核对客户端与服务端的鉴权 token。'; "
            + "elif printf '%s\\n' \"$LOG\" | grep -Eqi 'remote port.*(used|conflict)|port.*already.*used|proxy.*already exists|address already in use'; then "
            + "FRP_REASON='REMOTE_PORT_CONFLICT'; FRP_MESSAGE='远端端口已被占用或同名代理已存在，请更换端口或清理 frps 上的冲突代理。'; "
            + "elif printf '%s\\n' \"$LOG\" | grep -Eqi 'config.*(error|invalid)|parse.*config|syntax error'; then "
            + "FRP_REASON='CONFIG_INVALID'; FRP_MESSAGE='frpc 拒绝了当前配置，请修正配置后重试。'; "
            + "elif printf '%s\\n' \"$LOG\" | grep -Eqi 'proxy.*(error|failed)|start error|listener failed'; then "
            + "FRP_REASON='PROXY_REGISTRATION_FAILED'; FRP_MESSAGE='frpc 已尝试连接，但代理注册失败，请检查远端端口、代理名称及 frps 策略。'; fi; ";
    }

    private String captureRuleState(String query, String variable) {
        return "if (" + query + "); then " + variable + "=1; else " + variable + "=0; fi; ";
    }

    private String rollbackRuleIfChanged(String preVariable, String postVariable, String add, String remove) {
        return "if [ \"$" + preVariable + "\" != \"$" + postVariable + "\" ]; then "
            + "if [ \"$" + preVariable + "\" -eq 1 ]; then (" + add + "); else (" + remove + "); fi; fi; ";
    }

    private String shellQuote(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "'\"'\"'") + "'";
    }

    private String sourcePathFor(FrpProfile profile) {
        if (profile.getSourcePath() != null && profile.getSourcePath().matches("/etc/frp/conf\\.d/port_[0-9]+(?:_(?:tcp|udp))?\\.ini(?:\\.disabled)?")) {
            return profile.getSourcePath();
        }
        return String.format(Locale.US, "/etc/frp/conf.d/port_%d_%s.ini", profile.getRemotePort(), normalizeProtocol(profile.getProtocol()));
    }

    private String targetPathFor(FrpProfile profile) {
        if (!profile.hasPersistedIdentityChange()) return sourcePathFor(profile);
        return String.format(Locale.US, "/etc/frp/conf.d/port_%d_%s.ini%s", profile.getRemotePort(),
            normalizeProtocol(profile.getProtocol()), profile.isConfigFileDisabled() ? ".disabled" : "");
    }

    private String unitNameFor(FrpProfile profile) {
        if (profile.getUnitName() != null && profile.getUnitName().matches("frpc@[0-9]+(?:_(?:tcp|udp))?\\.service")) {
            return profile.getUnitName();
        }
        return profile.isLegacy() ? String.format(Locale.US, "frpc@%d.service", profile.getRemotePort())
            : String.format(Locale.US, "frpc@%d_%s.service", profile.getRemotePort(), normalizeProtocol(profile.getProtocol()));
    }

    private String normalizeProtocol(String protocol) { return protocol == null ? "tcp" : protocol.toLowerCase(Locale.US); }
    private String sha256(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(Locale.US, "%02x", b));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private String operationName(String value) {
        if ("start".equals(value)) return "启动";
        if ("restart".equals(value)) return "重启";
        if ("stop".equals(value)) return "暂停";
        return "删除";
    }

    public static class BatchCommandPlan {
        private final String summary;
        private final String command;
        public BatchCommandPlan(String summary, String command) { this.summary = summary; this.command = command; }
        public String getSummary() { return summary; }
        public String getCommand() { return command; }
    }
}
