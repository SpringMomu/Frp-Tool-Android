package io.momu.frpmanager.manager.model;

public class CommandExecutionResult {

    private final String command;
    private final String output;

    public CommandExecutionResult(String command, String output) {
        this.command = command;
        this.output = output;
    }

    public String getCommand() {
        return command;
    }

    public String getOutput() {
        return output;
    }
}
