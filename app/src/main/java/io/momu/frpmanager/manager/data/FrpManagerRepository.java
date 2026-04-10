package io.momu.frpmanager.manager.data;

import io.momu.frpmanager.FirewallManager;
import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.SshManager;
import io.momu.frpmanager.SshSettingsManager;
import io.momu.frpmanager.manager.model.ApplyChangesResult;
import io.momu.frpmanager.manager.model.BatchOperationResult;
import io.momu.frpmanager.manager.model.CommandExecutionResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FrpManagerRepository {

    private final SshManager sshManager;
    private final SshSettingsManager settingsManager;
    private final FrpServerCommandFactory commandFactory;
    private final FrpServerDataParser dataParser;

    public FrpManagerRepository(SshManager sshManager, SshSettingsManager settingsManager) {
        this.sshManager = sshManager;
        this.settingsManager = settingsManager;
        this.commandFactory = new FrpServerCommandFactory(settingsManager);
        this.dataParser = new FrpServerDataParser();
    }

    public String detectFirewallType() throws IOException {
        return sshManager.executeCommand(commandFactory.buildDetectFirewallCommand(), 20).trim();
    }

    public List<FrpProfile> loadProfiles(
        Map<String, Boolean> selectionState,
        List<FrpProfile> oldProfiles,
        List<FrpProfile> pendingProfiles,
        Set<String> lockedProfiles
    ) throws IOException {
        String result = sshManager.executeCommand(commandFactory.buildLoadProfilesCommand(), 45);
        return dataParser.parseLoadedProfiles(result, selectionState, oldProfiles, pendingProfiles, lockedProfiles);
    }

    public CommandExecutionResult applySingleProfileChange(
        FrpProfile profile,
        boolean restart,
        FirewallManager firewallManager
    ) throws IOException {
        String command = commandFactory.buildApplySingleProfileChangeCommand(
            profile,
            restart,
            shouldManageFirewall(firewallManager),
            firewallManager
        );
        String output = sshManager.executeCommand(command, 30);
        return new CommandExecutionResult(command, output);
    }

    public BatchOperationResult executeBatchOperation(
        String operationType,
        List<FrpProfile> targets,
        FirewallManager firewallManager
    ) throws IOException {
        FrpServerCommandFactory.BatchCommandPlan plan = commandFactory.buildBatchOperationCommand(
            operationType,
            targets,
            shouldManageFirewall(firewallManager),
            firewallManager
        );
        String output = sshManager.executeCommand(plan.getCommand(), 180);
        FrpServerDataParser.BatchOutputParseResult parsedOutput = dataParser.parseBatchOperationOutput(output);
        return new BatchOperationResult(
            plan.getCommand(),
            output,
            plan.getSummary(),
            parsedOutput.getBlocks(),
            parsedOutput.getStatusByProfileKey()
        );
    }

    public ApplyChangesResult applyChanges(List<FrpProfile> profilesToApply, FirewallManager firewallManager) throws IOException {
        String command = commandFactory.buildApplyChangesCommand(
            profilesToApply,
            shouldManageFirewall(firewallManager),
            firewallManager
        );
        String output = sshManager.executeCommand(command, 60);
        String verifyOutput = sshManager.executeCommand(commandFactory.buildVerifyConfigFilesCommand(profilesToApply), 30);
        return new ApplyChangesResult(command, output, verifyOutput, dataParser.parseFailedProfileKeys(verifyOutput));
    }

    public CommandExecutionResult executeSingleFirewallOperation(
        FrpProfile profile,
        String operationType,
        FirewallManager firewallManager
    ) throws IOException {
        String command = commandFactory.buildSingleFirewallCommand(profile, operationType, firewallManager);
        String output = sshManager.executeCommand(command, 60);
        return new CommandExecutionResult(command, output);
    }

    public CommandExecutionResult executeBatchFirewallOperation(
        String operationType,
        List<FrpProfile> targets,
        FirewallManager firewallManager
    ) throws IOException {
        String command = commandFactory.buildBatchFirewallCommand(operationType, targets, firewallManager);
        String output = sshManager.executeCommand(command, 180);
        return new CommandExecutionResult(command, output);
    }

    public String loadConfigFile(FrpProfile profile) throws IOException {
        return sshManager.executeCommand(commandFactory.buildReadConfigFileCommand(profile), 15);
    }

    public String buildFileContent(FrpProfile profile) {
        return commandFactory.buildFileContent(profile);
    }

    private boolean shouldManageFirewall(FirewallManager firewallManager) {
        return firewallManager != null && settingsManager.isFirewallManaged();
    }
}
