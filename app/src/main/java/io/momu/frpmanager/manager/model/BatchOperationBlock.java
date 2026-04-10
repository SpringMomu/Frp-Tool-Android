package io.momu.frpmanager.manager.model;

public class BatchOperationBlock {

    private final String profileKey;
    private final String content;
    private final PortExecutionStatus status;

    public BatchOperationBlock(String profileKey, String content, PortExecutionStatus status) {
        this.profileKey = profileKey;
        this.content = content;
        this.status = status;
    }

    public String getProfileKey() {
        return profileKey;
    }

    public String getContent() {
        return content;
    }

    public PortExecutionStatus getStatus() {
        return status;
    }
}
