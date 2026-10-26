package world.willfrog.datafrog.server.task;

import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.datafrog.idl.CreateGenerationTaskRequest;
import world.willfrog.datafrog.idl.CreateGenerationTaskResponse;
import world.willfrog.datafrog.idl.DubboGenerationTaskServiceTriple.GenerationTaskServiceImplBase;
import world.willfrog.datafrog.idl.GenerationTaskStatus;
import world.willfrog.datafrog.idl.GetGenerationTaskRequest;
import world.willfrog.datafrog.idl.GetGenerationTaskResponse;
import world.willfrog.datafrog.server.client.AgentRunClient;
import world.willfrog.datafrog.server.client.AuthValidationClient;
import world.willfrog.datafrog.server.client.CreditClient;

import java.util.UUID;

/**
 * 生成任务受理与受控运行（014-data-02）。
 *
 * create 处理顺序（方案 v2 经 wang 审查修订，CR 一轮后调整）：登录态校验 → 规格解析校验 →
 * 幂等去重（排在余额校验前：余额是创建当时的门槛，不该挡合法重试）→ 余额校验 →
 * 写任务表（ACCEPTED）→ 预扣记录 → 拉起受控运行（成功置 RUNNING，未拉起置 FAILED 并全额回补）。
 * 同对象互斥由部分唯一索引承载：插入冲突时先对阻塞任务做一次惰性推进再重试一次，
 * 仍冲突返回 GENERATION_IN_PROGRESS，不设后台巡检。
 *
 * 规格 JSON 的平台侧键名约定（与主仓库 context_json 扩展字段契约对齐后写死于此）：
 * instruction（行为边界，非空必填）、output_format（输出格式，选填）、
 * timeout_seconds（时限，必填，60 到 86400 秒）。
 */
@DubboService
@Service
public class GenerationTaskServiceImpl extends GenerationTaskServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(GenerationTaskServiceImpl.class);

    // 受理参数写为常量（本期不接热配置；预留配置键 datafrog.task.*，接入热配置时平移）。
    /** 附加费：拍板口径「每次生产除推理费用外额外加 3 到 5 美分」取上限，积分与美分换算比例待 frog 定。 */
    static final int SURCHARGE_CREDITS = 5;
    /** 推理费预估下限，用于受理时预扣；具体数值是开放项，待 frog 定积分口径后调整。 */
    static final int INFERENCE_FLOOR_CREDITS = 20;
    static final int PRECHARGE_CREDITS = INFERENCE_FLOOR_CREDITS + SURCHARGE_CREDITS;
    /** ACCEPTED 态任务的拉起宽限：超过此时长仍未写 run_id 视为创建途中进程死亡，惰性置失败终态并回补。 */
    static final long LAUNCH_GRACE_SECONDS = 120;

    static final long MIN_TIMEOUT_SECONDS = 60;
    static final long MAX_TIMEOUT_SECONDS = 86400;

    private static final String ERR_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String ERR_INVALID_SPEC = "INVALID_SPEC";
    private static final String ERR_CREDIT_INSUFFICIENT = "CREDIT_INSUFFICIENT";
    private static final String ERR_IN_PROGRESS = "GENERATION_IN_PROGRESS";
    private static final String ERR_LAUNCH_FAILED = "GENERATION_LAUNCH_FAILED";
    private static final String ERR_RUN_FAILED = "GENERATION_RUN_FAILED";
    private static final String ERR_TASK_TIMEOUT = "TASK_TIMEOUT";
    private static final String ERR_TASK_NOT_FOUND = "TASK_NOT_FOUND";
    /** 后端故障（读余额、读运行状态等基础设施错误），与业务拒绝分开（wang CR 口径第 2 条）。 */
    private static final String ERR_INTERNAL = "INTERNAL";

    private final AuthValidationClient authValidationClient;
    private final CreditClient creditClient;
    private final AgentRunClient agentRunClient;
    private final GenerationTaskMapper taskMapper;

    public GenerationTaskServiceImpl(AuthValidationClient authValidationClient,
                                     CreditClient creditClient,
                                     AgentRunClient agentRunClient,
                                     GenerationTaskMapper taskMapper) {
        this.authValidationClient = authValidationClient;
        this.creditClient = creditClient;
        this.agentRunClient = agentRunClient;
        this.taskMapper = taskMapper;
    }

    @Override
    public CreateGenerationTaskResponse createGenerationTask(CreateGenerationTaskRequest request) {
        // 第一跳：登录态校验。createRun 服务端不校验调用方身份，防伪造责任在本服务这一侧。
        AuthValidationClient.AuthValidationResult auth = authValidationClient.validate(request.getSessionToken());
        if (!auth.isValid()) {
            return createError(ERR_UNAUTHENTICATED, "session token invalid: " + auth.getMessage());
        }
        String userId = auth.getUserId();

        // 规格解析校验。
        if (request.getTargetObjectName().isBlank()) {
            return createError(ERR_INVALID_SPEC, "target_object_name is blank");
        }
        if (request.getClientRequestId().isBlank()) {
            return createError(ERR_INVALID_SPEC, "client_request_id is blank");
        }
        Struct spec;
        try {
            Struct.Builder builder = Struct.newBuilder();
            JsonFormat.parser().ignoringUnknownFields().merge(request.getSpecJson(), builder);
            spec = builder.build();
        } catch (Exception e) {
            return createError(ERR_INVALID_SPEC, "spec_json is not valid JSON object: " + e.getMessage());
        }
        String instruction = spec.getFieldsOrDefault("instruction", com.google.protobuf.Value.getDefaultInstance())
                .getStringValue();
        if (instruction.isBlank()) {
            return createError(ERR_INVALID_SPEC, "spec_json.instruction is blank");
        }
        double timeoutRaw = spec.getFieldsOrDefault("timeout_seconds", com.google.protobuf.Value.getDefaultInstance())
                .getNumberValue();
        long timeoutSeconds = (long) timeoutRaw;
        if (timeoutRaw != timeoutSeconds || timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            return createError(ERR_INVALID_SPEC,
                    "spec_json.timeout_seconds must be an integer in [" + MIN_TIMEOUT_SECONDS + ", " + MAX_TIMEOUT_SECONDS + "]");
        }

        // 幂等去重（在余额校验之前）：命中直接返回已有任务，不新建也不重复计费。
        GenerationTaskEntity existing = taskMapper.findByClientRequestId(userId, request.getClientRequestId());
        if (existing != null) {
            return CreateGenerationTaskResponse.newBuilder()
                    .setTaskId(existing.getId())
                    .setStatus(toProtoStatus(existing.getStatus()))
                    .setDeduplicated(true)
                    .build();
        }

        // 余额校验：剩余积分需覆盖预扣额。读失败是后端故障，不映射成余额不足。
        int remaining;
        try {
            remaining = creditClient.getRemainingCredits(userId);
        } catch (Exception e) {
            log.warn("get remaining credits failed: user={} err={}", userId, e.toString());
            return createError(ERR_INTERNAL, "failed to read remaining credits");
        }
        if (remaining < PRECHARGE_CREDITS) {
            return createError(ERR_CREDIT_INSUFFICIENT,
                    "remaining credits " + remaining + " < precharge " + PRECHARGE_CREDITS);
        }

        // 写任务表（ACCEPTED，规格快照）。唯一索引冲突时区分去重竞态命中与同对象互斥。
        GenerationTaskEntity task = new GenerationTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setUserId(userId);
        task.setTargetObjectName(request.getTargetObjectName());
        task.setSpecJson(request.getSpecJson());
        task.setClientRequestId(request.getClientRequestId());
        task.setStatus(GenerationTaskStateMachine.ACCEPTED);
        task.setCreditCharged(PRECHARGE_CREDITS);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            CreateGenerationTaskResponse conflict = handleInsertConflict(task);
            if (conflict != null) {
                return conflict;
            }
            // conflict 为空表示惰性推进后重试插入已成功，继续走预扣与拉起运行。
        }

        // 预扣（本地直通期间只记录意图，字段照常流转）。
        creditClient.debit(userId, PRECHARGE_CREDITS, task.getId());

        // 拉起受控运行；幂等键填任务编号，防止重试造成重复运行。
        try {
            AgentRunMessage run = agentRunClient.createRun(userId, instruction, request.getSpecJson(), task.getId());
            taskMapper.updateRunning(task.getId(), GenerationTaskStateMachine.RUNNING, run.getId());
            return CreateGenerationTaskResponse.newBuilder()
                    .setTaskId(task.getId())
                    .setStatus(GenerationTaskStatus.RUNNING)
                    .build();
        } catch (Exception e) {
            log.warn("createRun failed, task marked FAILED with full refund: task={} err={}", task.getId(), e.toString());
            finalizeTerminal(task, GenerationTaskStateMachine.FAILED, ERR_LAUNCH_FAILED,
                    "createRun failed: " + e.getMessage(), null, 0);
            // 任务行已落库（FAILED 且预扣全额回补），应答带上 task_id 方便调用方查询与核对。
            return CreateGenerationTaskResponse.newBuilder()
                    .setTaskId(task.getId())
                    .setStatus(GenerationTaskStatus.FAILED)
                    .setErrorCode(ERR_LAUNCH_FAILED)
                    .setErrorMessage("failed to launch agent run: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 插入冲突处理：先分辨是去重竞态命中还是同对象互斥。互斥时对阻塞任务做一次
     * 惰性推进（已终态或已超时则置终态释放互斥），再重试一次插入；仍冲突报 GENERATION_IN_PROGRESS。
     * 返回 null 表示重试插入已成功，调用方继续走预扣与拉起运行。
     */
    private CreateGenerationTaskResponse handleInsertConflict(GenerationTaskEntity task) {
        GenerationTaskEntity dedupHit = taskMapper.findByClientRequestId(task.getUserId(), task.getClientRequestId());
        if (dedupHit != null) {
            return CreateGenerationTaskResponse.newBuilder()
                    .setTaskId(dedupHit.getId())
                    .setStatus(toProtoStatus(dedupHit.getStatus()))
                    .setDeduplicated(true)
                    .build();
        }
        GenerationTaskEntity blocker = taskMapper.findActiveByTargetObjectName(task.getTargetObjectName());
        if (blocker != null) {
            advanceBlockingTask(blocker);
        }
        try {
            taskMapper.insert(task);
            return null;
        } catch (DuplicateKeyException e2) {
            return createError(ERR_IN_PROGRESS,
                    "another generation task is active for target object " + task.getTargetObjectName());
        }
    }

    /**
     * 惰性推进阻塞任务：RUNNING 的读运行状态，运行已终态或超规格时限则置终态；
     * ACCEPTED 的超拉起宽限则置失败终态（与查询路径共用同一段判定）。
     */
    private void advanceBlockingTask(GenerationTaskEntity blocker) {
        try {
            if (GenerationTaskStateMachine.RUNNING.equals(blocker.getStatus()) && blocker.getRunId() != null) {
                AgentRunStatusMessage runStatus = agentRunClient.getRunStatus(blocker.getUserId(), blocker.getRunId());
                settleByRunStatus(blocker, runStatus);
            } else {
                advanceAcceptedIfStale(blocker);
            }
        } catch (Exception ex) {
            log.warn("lazy advance of blocking task failed: task={} err={}", blocker.getId(), ex.toString());
        }
    }

    /**
     * ACCEPTED 死任务的惰性推进：超过拉起宽限仍未写 run_id，视为创建途中进程死亡，
     * 置 FAILED（GENERATION_LAUNCH_FAILED）并全额回补预扣。互斥冲突路径与查询路径共用。
     */
    private void advanceAcceptedIfStale(GenerationTaskEntity task) {
        if (!GenerationTaskStateMachine.ACCEPTED.equals(task.getStatus())
                || task.getCreatedAt() == null
                || task.getCreatedAt().plusSeconds(LAUNCH_GRACE_SECONDS).isAfter(GenerationTaskStateMachine.nowUtc())) {
            return;
        }
        finalizeTerminal(task, GenerationTaskStateMachine.FAILED, ERR_LAUNCH_FAILED,
                "accepted but never launched within grace " + LAUNCH_GRACE_SECONDS + "s", null, 0);
    }

    @Override
    public GetGenerationTaskResponse getGenerationTask(GetGenerationTaskRequest request) {
        AuthValidationClient.AuthValidationResult auth = authValidationClient.validate(request.getSessionToken());
        if (!auth.isValid()) {
            return getQueryError(ERR_UNAUTHENTICATED, "session token invalid: " + auth.getMessage());
        }
        GenerationTaskEntity task = taskMapper.findById(request.getTaskId());
        // 属主校验：任务只对本主可见，查别人的任务与查不存在的任务同一应答（wang CR 建议第 1 条）。
        if (task == null || !auth.getUserId().equals(task.getUserId())) {
            return getQueryError(ERR_TASK_NOT_FOUND, "task not found: " + request.getTaskId());
        }

        // 非终态任务做惰性推进：ACCEPTED 超宽限置失败终态；RUNNING 读运行实态，
        // 运行已终态或超规格时限则置终态并结算。
        String runStatusJson = "";
        if (!GenerationTaskStateMachine.isTerminal(task.getStatus())) {
            if (GenerationTaskStateMachine.RUNNING.equals(task.getStatus()) && task.getRunId() != null) {
                try {
                    AgentRunStatusMessage runStatus = agentRunClient.getRunStatus(task.getUserId(), task.getRunId());
                    if (isRunTerminal(runStatus.getStatus()) || isTaskTimedOut(task)) {
                        settleByRunStatus(task, runStatus);
                    } else {
                        runStatusJson = JsonFormat.printer().print(runStatus);
                    }
                } catch (Exception e) {
                    log.warn("getRunStatus failed, task left as-is: task={} err={}", task.getId(), e.toString());
                }
            } else {
                advanceAcceptedIfStale(task);
            }
            task = taskMapper.findById(task.getId());
        }

        return GetGenerationTaskResponse.newBuilder()
                .setTaskId(task.getId())
                .setStatus(toProtoStatus(task.getStatus()))
                .setRunId(task.getRunId() == null ? "" : task.getRunId())
                .setIncomplete(Boolean.TRUE.equals(task.getIncomplete()))
                .setErrorCode(task.getErrorCode() == null ? "" : task.getErrorCode())
                .setErrorMessage(task.getErrorMessage() == null ? "" : task.getErrorMessage())
                .setRunStatusJson(runStatusJson)
                .setCreditCharged(task.getCreditCharged() == null ? 0 : task.getCreditCharged())
                .setCreditSettled(task.getCreditSettled() == null ? 0 : task.getCreditSettled())
                .setCreatedAtEpoch(task.getCreatedAt() == null ? 0 : task.getCreatedAt().toEpochSecond())
                .setFinishedAtEpoch(task.getFinishedAt() == null ? 0 : task.getFinishedAt().toEpochSecond())
                .build();
    }

    /**
     * 按运行实态给 RUNNING 任务置终态并结算（结算三档，拍板事项第 6 条「按已消耗结算」）：
     * 运行成功（COMPLETED/PARTIAL）实结 = 已消耗 + 附加费；运行失败按已消耗实结；
     * 超规格时限置 TASK_TIMEOUT，按状态摘要里已消耗实结，并尽力取消运行（不再继续烧积分）。
     * 已消耗的权威源是 getStatus 返回的 total_credits_consumed，与主仓库结算管线同源；
     * 不用 getRunCost（货币口径不是积分）。运行仍在进行且未超时则不动作。
     */
    private void settleByRunStatus(GenerationTaskEntity task, AgentRunStatusMessage runStatus) {
        int consumed = runStatus.getTotalCreditsConsumed();
        String runState = runStatus.getStatus();
        if ("COMPLETED".equals(runState) || "PARTIAL".equals(runState)) {
            finalizeTerminal(task, GenerationTaskStateMachine.SUCCEEDED, null, null, runStatus, consumed + SURCHARGE_CREDITS);
        } else if ("FAILED".equals(runState) || "CANCELED".equals(runState) || "EXPIRED".equals(runState)) {
            finalizeTerminal(task, GenerationTaskStateMachine.FAILED, ERR_RUN_FAILED,
                    "agent run terminated with status " + runState, runStatus, consumed);
        } else if (isTaskTimedOut(task)) {
            // 先取消运行再置终态：任务置终后平台不再结算，不能让运行继续烧积分。
            // 取消是尽力动作，失败记日志不阻塞置终（运行侧还有 EXPIRED 机制）。
            try {
                agentRunClient.cancelRun(task.getUserId(), task.getRunId());
            } catch (Exception e) {
                log.warn("cancelRun on timeout failed (task finalization proceeds): task={} run={} err={}",
                        task.getId(), task.getRunId(), e.toString());
            }
            finalizeTerminal(task, GenerationTaskStateMachine.FAILED, ERR_TASK_TIMEOUT,
                    "run exceeded spec timeout", runStatus, consumed);
        }
    }

    /**
     * 写终态并记录结算意图；回补金额 = 预扣 - 实结，由 CreditClient 结算时一并记录。
     * updateTerminal 带非终态守卫，并发惰性推进只有一个能写上；写不上的跳过结算，防止重复 settle。
     */
    private void finalizeTerminal(GenerationTaskEntity task, String status, String errorCode, String errorMessage,
                                  AgentRunStatusMessage runStatus, int settledCredits) {
        if (!GenerationTaskStateMachine.canTransition(task.getStatus(), status)) {
            log.warn("illegal transition skipped: task={} from={} to={}", task.getId(), task.getStatus(), status);
            return;
        }
        task.setStatus(status);
        if (runStatus != null) {
            task.setRunId(runStatus.getId());
        }
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);
        task.setCreditSettled(settledCredits);
        task.setFinishedAt(GenerationTaskStateMachine.nowUtc());
        int updated = taskMapper.updateTerminal(task);
        if (updated == 0) {
            log.info("terminal update lost to concurrent finalization, settle skipped: task={} to={}", task.getId(), status);
            return;
        }
        creditClient.settle(task.getUserId(),
                task.getCreditCharged() == null ? 0 : task.getCreditCharged(), settledCredits, task.getId());
    }

    private boolean isRunTerminal(String runStatus) {
        return "COMPLETED".equals(runStatus) || "PARTIAL".equals(runStatus)
                || "FAILED".equals(runStatus) || "CANCELED".equals(runStatus) || "EXPIRED".equals(runStatus);
    }

    /** 惰性超时判定所需的规格时限从任务表的规格快照读；快照在受理时已校验，解析失败按未超时处理。 */
    private boolean isTaskTimedOut(GenerationTaskEntity task) {
        try {
            Struct.Builder builder = Struct.newBuilder();
            JsonFormat.parser().ignoringUnknownFields().merge(task.getSpecJson(), builder);
            long timeoutSeconds = (long) builder.getFieldsOrDefault("timeout_seconds",
                    com.google.protobuf.Value.getDefaultInstance()).getNumberValue();
            return GenerationTaskStateMachine.isTimedOut(task, timeoutSeconds, GenerationTaskStateMachine.nowUtc());
        } catch (Exception e) {
            return false;
        }
    }

    private static GenerationTaskStatus toProtoStatus(String status) {
        return switch (status) {
            case GenerationTaskStateMachine.ACCEPTED -> GenerationTaskStatus.ACCEPTED;
            case GenerationTaskStateMachine.RUNNING -> GenerationTaskStatus.RUNNING;
            case GenerationTaskStateMachine.SUCCEEDED -> GenerationTaskStatus.SUCCEEDED;
            case GenerationTaskStateMachine.FAILED -> GenerationTaskStatus.FAILED;
            case GenerationTaskStateMachine.FAILED_INCOMPLETE -> GenerationTaskStatus.FAILED_INCOMPLETE;
            default -> GenerationTaskStatus.GENERATION_TASK_STATUS_UNSPECIFIED;
        };
    }

    private static CreateGenerationTaskResponse createError(String code, String message) {
        return CreateGenerationTaskResponse.newBuilder().setErrorCode(code).setErrorMessage(message).build();
    }

    private static GetGenerationTaskResponse getQueryError(String code, String message) {
        return GetGenerationTaskResponse.newBuilder().setQueryErrorCode(code).setQueryErrorMessage(message).build();
    }
}
