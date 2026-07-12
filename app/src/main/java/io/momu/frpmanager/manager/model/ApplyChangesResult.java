package io.momu.frpmanager.manager.model;

import java.util.Collections;
import java.util.List;

public class ApplyChangesResult extends CommandExecutionResult {

	private final String verifyOutput;
	private final List<String> failedProfileKeys;
	private final List<String> successfulProfileKeys;

	public ApplyChangesResult(String command, String output, String errorOutput, Integer exitCode, String exitSignal, boolean timedOut,
		String verifyOutput, List<String> failedProfileKeys, List<String> successfulProfileKeys) {
		super(command, output, errorOutput, exitCode, exitSignal, timedOut);
		this.verifyOutput = verifyOutput;
		this.failedProfileKeys = failedProfileKeys == null ? Collections.emptyList() : failedProfileKeys;
		this.successfulProfileKeys = successfulProfileKeys == null ? Collections.emptyList() : successfulProfileKeys;
    }

    public String getVerifyOutput() {
        return verifyOutput;
    }

	public List<String> getFailedProfileKeys() {
        return failedProfileKeys;
	}

	public List<String> getSuccessfulProfileKeys() { return successfulProfileKeys; }

    public boolean isSuccessful() {
		return !isTimedOut() && getExitSignal() == null && getExitCode() != null && getExitCode() == 0 && failedProfileKeys.isEmpty();
    }
}
