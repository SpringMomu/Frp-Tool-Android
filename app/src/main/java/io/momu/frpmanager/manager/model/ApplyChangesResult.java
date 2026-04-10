package io.momu.frpmanager.manager.model;

import java.util.Collections;
import java.util.List;

public class ApplyChangesResult extends CommandExecutionResult {

    private final String verifyOutput;
    private final List<String> failedProfileKeys;

    public ApplyChangesResult(String command, String output, String verifyOutput, List<String> failedProfileKeys) {
        super(command, output);
        this.verifyOutput = verifyOutput;
        this.failedProfileKeys = failedProfileKeys == null ? Collections.emptyList() : failedProfileKeys;
    }

    public String getVerifyOutput() {
        return verifyOutput;
    }

    public List<String> getFailedProfileKeys() {
        return failedProfileKeys;
    }

    public boolean isSuccessful() {
        return failedProfileKeys.isEmpty();
    }
}
