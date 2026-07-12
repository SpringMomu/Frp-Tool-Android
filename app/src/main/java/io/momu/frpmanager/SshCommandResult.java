package io.momu.frpmanager;

/** Complete result of one remote command.  Non-zero exit codes are never success. */
public final class SshCommandResult {
    private final String stdout;
    private final String stderr;
	private final Integer exitCode;
	private final String exitSignal;
    private final boolean timedOut;
    private final long durationMillis;

	public SshCommandResult(String stdout, String stderr, Integer exitCode, String exitSignal, boolean timedOut, long durationMillis) {
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
		this.exitCode = exitCode;
		this.exitSignal = exitSignal;
        this.timedOut = timedOut;
        this.durationMillis = durationMillis;
    }

    public String getStdout() { return stdout; }
    public String getStderr() { return stderr; }
	public Integer getExitCode() { return exitCode; }
	public String getExitSignal() { return exitSignal; }
    public boolean isTimedOut() { return timedOut; }
    public long getDurationMillis() { return durationMillis; }
	public boolean isSuccessful() { return !timedOut && exitSignal == null && exitCode != null && exitCode == 0; }
}
