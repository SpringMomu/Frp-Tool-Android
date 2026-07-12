package io.momu.frpmanager.manager.model;

public class BatchOperationBlock {

    private final String profileKey;
    private final String content;
    private final PortExecutionStatus status;
    private final String reason;

    public BatchOperationBlock(String profileKey, String content, PortExecutionStatus status) {
        this(profileKey, content, status, null);
    }

    public BatchOperationBlock(String profileKey, String content, PortExecutionStatus status, String reason) {
        this.profileKey = profileKey;
        this.content = content;
        this.status = status;
        this.reason = reason;
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

    public String getReason() {
        return reason;
    }
}
