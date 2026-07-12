package io.momu.frpmanager.manager.data;

import io.momu.frpmanager.FirewallManager;
import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.SensitiveDataRedactor;
import io.momu.frpmanager.SshCommandResult;
import io.momu.frpmanager.SshManager;
import io.momu.frpmanager.SshSettingsManager;
import io.momu.frpmanager.manager.model.ApplyChangesResult;
import io.momu.frpmanager.manager.model.BatchOperationResult;
import io.momu.frpmanager.manager.model.CommandExecutionResult;
import io.momu.frpmanager.manager.util.FrpProfileKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps frpc management separate from the SSH endpoint that owns frps firewall rules. */
public class FrpManagerRepository {

    private final SshManager sshManager;
    private final SshManager frpsFirewallSshManager;
    private final SshSettingsManager settingsManager;
    private final FrpServerCommandFactory commandFactory;
    private final FrpServerDataParser dataParser;

    public FrpManagerRepository(SshManager sshManager, SshSettingsManager settingsManager) {
        this(sshManager, null, settingsManager);
    }

    public FrpManagerRepository(SshManager sshManager, SshManager frpsFirewallSshManager,
        SshSettingsManager settingsManager) {
        this.sshManager = sshManager;
        this.frpsFirewallSshManager = frpsFirewallSshManager;
        this.settingsManager = settingsManager;
        this.commandFactory = new FrpServerCommandFactory(settingsManager);
        this.dataParser = new FrpServerDataParser();
    }

    public String detectFirewallType() throws IOException {
        if (!isFirewallManagementAvailable()) return "none";
        return frpsFirewallSshManager.executeCommand(commandFactory.buildDetectFirewallCommand(), 20).trim();
    }

    public List<FrpProfile> loadProfiles(Map<String, Boolean> selectionState, List<FrpProfile> oldProfiles,
        List<FrpProfile> pendingProfiles, Set<String> lockedProfiles, FirewallManager firewallManager) throws IOException {
        String output = sshManager.executeCommand(commandFactory.buildLoadProfilesCommand(), 45);
        List<FrpProfile> profiles = dataParser.parseLoadedProfiles(output, selectionState, oldProfiles, pendingProfiles, lockedProfiles);
        if (isFirewallManagementAvailable() && firewallManager != null) {
            try {
                SshCommandResult rules = frpsFirewallSshManager.executeCommandResult(commandFactory.buildReadFirewallRulesCommand(), 30);
                if (rules.isSuccessful()) dataParser.applyFirewallRuleStatuses(profiles, rules.getStdout());
                else for (FrpProfile profile : profiles) profile.setFirewallStatus(FrpProfile.FIREWALL_UNKNOWN);
            } catch (IOException exception) {
                for (FrpProfile profile : profiles) profile.setFirewallStatus(FrpProfile.FIREWALL_UNKNOWN);
            }
        }
        return profiles;
    }

    public CommandExecutionResult applySingleProfileChange(FrpProfile profile, boolean restart,
        FirewallManager firewallManager) throws IOException {
        final String command;
        try {
            command = commandFactory.buildApplySingleProfileChangeCommand(profile, restart, false, null);
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        SshCommandResult result = sshManager.executeCommandResult(command, 60);
        if (!result.isSuccessful()) throw commandFailed(result);
        return commandResult("已原子写入并校验配置", result);
    }

    public BatchOperationResult executeBatchOperation(String operationType, List<FrpProfile> targets,
        FirewallManager firewallManager) throws IOException {
        FrpServerCommandFactory.BatchCommandPlan plan = commandFactory.buildBatchOperationCommand(operationType, targets, false, null);
        SshCommandResult result = sshManager.executeCommandResult(plan.getCommand(), 180);
        FrpServerDataParser.BatchOutputParseResult parsed = dataParser.parseBatchOperationOutput(
            result.getStdout() + "\n" + result.getStderr());
		return new BatchOperationResult("批量" + operationType + "操作", result.getStdout(), result.getStderr(),
			result.getExitCode(), result.getExitSignal(), result.isTimedOut(), plan.getSummary(), parsed.getBlocks(), parsed.getStatusByProfileKey());
    }

    public ApplyChangesResult applyChanges(List<FrpProfile> profilesToApply, FirewallManager firewallManager) throws IOException {
        final String command;
        try {
            command = commandFactory.buildApplyChangesCommand(profilesToApply, false, null);
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        SshCommandResult apply = sshManager.executeCommandResult(command, 120);
        String combined = apply.getStdout() + "\n" + apply.getStderr();
        List<String> successful = dataParser.parseSuccessfulProfileKeys(combined);
        List<String> failed = new ArrayList<>(dataParser.parseFailedProfileKeys(combined));
        for (FrpProfile profile : profilesToApply) {
            String key = FrpProfileKey.from(profile);
            if (!successful.contains(key) && !failed.contains(key)) failed.add(key);
        }
        String verifyOutput = "";
        if (!successful.isEmpty()) {
            List<FrpProfile> successfulProfiles = new ArrayList<>();
            for (FrpProfile profile : profilesToApply) if (successful.contains(FrpProfileKey.from(profile))) successfulProfiles.add(profile);
            SshCommandResult verification = sshManager.executeCommandResult(
                commandFactory.buildVerifyConfigFilesCommand(successfulProfiles), 60);
            verifyOutput = verification.getStdout() + "\n" + verification.getStderr();
            for (String key : dataParser.parseFailedProfileKeys(verifyOutput)) {
                successful.remove(key);
                if (!failed.contains(key)) failed.add(key);
            }
        }
		return new ApplyChangesResult("批量原子写入并验证配置", apply.getStdout(), apply.getStderr(),
			apply.getExitCode(), apply.getExitSignal(), apply.isTimedOut(), verifyOutput, failed, successful);
    }

    public CommandExecutionResult executeSingleFirewallOperation(FrpProfile profile, String operationType,
        FirewallManager firewallManager) throws IOException {
        requireFirewallManagement(firewallManager);
        String command = commandFactory.buildSingleFirewallCommand(profile, operationType, firewallManager);
        SshCommandResult result = frpsFirewallSshManager.executeCommandResult(command, 60);
        if (!result.isSuccessful()) throw commandFailed(result);
		return commandResult("在 frps 防火墙主机上" + operationType + "本工具规则", result);
    }

    public CommandExecutionResult executeBatchFirewallOperation(String operationType, List<FrpProfile> targets,
        FirewallManager firewallManager) throws IOException {
        requireFirewallManagement(firewallManager);
        String command = commandFactory.buildBatchFirewallCommand(operationType, targets, firewallManager);
        SshCommandResult result = frpsFirewallSshManager.executeCommandResult(command, 180);
        if (!result.isSuccessful()) throw commandFailed(result);
		return commandResult("在 frps 防火墙主机上批量" + operationType + "本工具规则", result);
    }

    public String loadConfigFile(FrpProfile profile) throws IOException {
        return sshManager.executeCommand(commandFactory.buildReadConfigFileCommand(profile), 15);
    }
    public String buildFileContent(FrpProfile profile) { return commandFactory.buildFileContent(profile); }
    public boolean isFirewallManagementAvailable() {
        return frpsFirewallSshManager != null && settingsManager.isFirewallManaged();
    }

    private void requireFirewallManagement(FirewallManager manager) throws IOException {
        if (!isFirewallManagementAvailable() || manager == null) {
			throw new IOException("防火墙通道未就绪，请检查目标 SSH 地址、认证信息和主机指纹");
        }
    }
    private CommandExecutionResult commandResult(String description, SshCommandResult result) {
		return new CommandExecutionResult(description, SensitiveDataRedactor.redact(result.getStdout()),
			SensitiveDataRedactor.redact(result.getStderr()), result.getExitCode(), result.getExitSignal(), result.isTimedOut());
    }
    private IOException commandFailed(SshCommandResult result) {
		String message = result.isTimedOut() ? "远端命令超时并已取消"
			: result.getExitSignal() != null ? "远端命令被信号终止：" + result.getExitSignal()
			: "远端命令失败，退出码=" + result.getExitCode();
        String stderr = SensitiveDataRedactor.redact(result.getStderr()).trim();
        return new IOException(stderr.isEmpty() ? message : message + ": " + stderr);
    }
}
