package world.willfrog.datafrog.server.task;

import java.time.OffsetDateTime;

/**
 * 修改任务表 datafrog_modification_task 的实体映射（014-data-05）。
 * 字段语义以 db/datafrog-modification-task-v1.sql 的列注释为准。
 */
public class ModificationTaskEntity {

    private String id;
    private String userId;
    private String baseObjectId;
    private Integer baseVersion;
    private String versionNote;
    private String specJson;
    private String clientRequestId;
    private String status;
    private String runId;
    private Boolean incomplete;
    private String errorCode;
    private String errorMessage;
    private Integer creditCharged;
    private Integer creditSettled;
    private Integer registeredVersion;
    private Boolean columnsConfirmed;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime finishedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getBaseObjectId() { return baseObjectId; }
    public void setBaseObjectId(String baseObjectId) { this.baseObjectId = baseObjectId; }

    public Integer getBaseVersion() { return baseVersion; }
    public void setBaseVersion(Integer baseVersion) { this.baseVersion = baseVersion; }

    public String getVersionNote() { return versionNote; }
    public void setVersionNote(String versionNote) { this.versionNote = versionNote; }

    public String getSpecJson() { return specJson; }
    public void setSpecJson(String specJson) { this.specJson = specJson; }

    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public Boolean getIncomplete() { return incomplete; }
    public void setIncomplete(Boolean incomplete) { this.incomplete = incomplete; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getCreditCharged() { return creditCharged; }
    public void setCreditCharged(Integer creditCharged) { this.creditCharged = creditCharged; }

    public Integer getCreditSettled() { return creditSettled; }
    public void setCreditSettled(Integer creditSettled) { this.creditSettled = creditSettled; }

    public Integer getRegisteredVersion() { return registeredVersion; }
    public void setRegisteredVersion(Integer registeredVersion) { this.registeredVersion = registeredVersion; }

    public Boolean getColumnsConfirmed() { return columnsConfirmed; }
    public void setColumnsConfirmed(Boolean columnsConfirmed) { this.columnsConfirmed = columnsConfirmed; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
