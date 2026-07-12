package io.momu.frpmanager.manager.model;

public class CommandExecutionResult {

	private final String command;
	private final String output;
	private final String errorOutput;
	private final Integer exitCode;
	private final String exitSignal;
	private final boolean timedOut;

	public CommandExecutionResult(String command, String output) {
		this(command, output, "", 0, null, false);
	}

	public CommandExecutionResult(String command, String output, String errorOutput, Integer exitCode, String exitSignal, boolean timedOut) {
		this.command = command;
		this.output = output == null ? "" : output;
		this.errorOutput = errorOutput == null ? "" : errorOutput;
		this.exitCode = exitCode;
		this.exitSignal = exitSignal;
		this.timedOut = timedOut;
    }

    public String getCommand() {
        return command;
    }

	public String getOutput() {
		return output;
	}

	public String getErrorOutput() { return errorOutput; }
	public Integer getExitCode() { return exitCode; }
	public String getExitSignal() { return exitSignal; }
	public boolean isTimedOut() { return timedOut; }
	public boolean isSuccessful() { return !timedOut && exitSignal == null && exitCode != null && exitCode == 0; }
}
