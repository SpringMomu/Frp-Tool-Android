package io.momu.frpmanager.manager.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class BatchOperationResult extends CommandExecutionResult {

    private final String summary;
    private final List<BatchOperationBlock> blocks;
    private final Map<String, PortExecutionStatus> statusByProfileKey;

    public BatchOperationResult(
        String command,
        String output,
        String summary,
        List<BatchOperationBlock> blocks,
        Map<String, PortExecutionStatus> statusByProfileKey
    ) {
        super(command, output);
        this.summary = summary;
        this.blocks = blocks == null ? Collections.emptyList() : blocks;
        this.statusByProfileKey = statusByProfileKey == null ? Collections.emptyMap() : statusByProfileKey;
    }

    public String getSummary() {
        return summary;
    }

    public List<BatchOperationBlock> getBlocks() {
        return blocks;
    }

    public Map<String, PortExecutionStatus> getStatusByProfileKey() {
        return statusByProfileKey;
    }
}
