package world.willfrog.datafrog.server.task;

import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.datafrog.server.client.AgentRunClient;
import world.willfrog.datafrog.server.client.AuthValidationClient;
import world.willfrog.datafrog.server.client.CreditClient;
import world.willfrog.datafrog.server.httpapi.VisibilityGuard;
import world.willfrog.datafrog.server.objectdata.service.DataObjectRegistryService;
import world.willfrog.datafrog.server.objectdata.service.ObjectStoreExceptions;

import java.util.UUID;

/**
 * 修改任务受理与受控运行（014-data-05 方案 D1）。
 *
 * 受理管线镜像 02 的 GenerationTaskServiceImpl，差异三处（方案 D1 选择镜像建表的理由）：
 * 互斥键是 base_object_id（生成任务按目标对象名）；受理时把基准版本解析成具体版本号
 * 写入任务表（缺省为对象当前版本），登记时只认这个号；产物登记挂钩——运行成功置
 * SUCCEEDED 后由 {@link ModificationRegistrationService} 把产物升版到基准对象上。
 *
 * 与生成任务之间不设跨表互斥：由登记段的基准版本守卫拦截（基准版本被顶掉时任务
 * FAILED_INCOMPLETE，不升版），不需要「修改任务与生成任务互斥」这类规则（bob 必写句）。
 *
 * 本轮不开远程契约（bob 裁定）：不注册 Dubbo 服务、不建 proto，HTTP 控制器
 * （ModificationTaskController）进程内直调本类；请求/应答用嵌套 record 承载，字段语义
 * 与 02 的 proto 消息一一对应。以后真出现跨进程调用再补契约，补的时候不改已验收的
 * HTTP 字段名。
 *
 * 规格 JSON 的键在 02 约定（instruction、output_format、timeout_seconds、model）之外
 * 新增三个：base_object_id（必填）、base_version（选填，缺省为对象当前版本）、
 * version_note（必填非空，升版事由；instruction 是修改需求本身，version_note 是登记
 * 口径的升版事由，两个都要）。三键同时进任务表列，不靠 JSON 回查。
 */
@Service
public class ModificationTaskServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(ModificationTaskServiceImpl.class);

    // 计费常量与 02 完全同值同型（方案 D11：修改任务的预扣、结算三档、附加费常量与 02 同值，
    // 积分扣减 RPC 落地前沿用 02 的本地直通先例，落地后经同一次切换完成，不单独改）。
    static final int SURCHARGE_CREDITS = 5;
    static final int INFERENCE_FLOOR_CREDITS = 20;
    static final int PRECHARGE_CREDITS = INFERENCE_FLOOR_CREDITS + SURCHARGE_CREDITS;
    /** ACCEPTED 态任务的拉起宽限：超过此时长仍未写 run_id 视为创建途中进程死亡，惰性置失败终态并回补。 */
    static final long LAUNCH_GRACE_SECONDS = 120;

    static final long MIN_TIMEOUT_SECONDS = 60;
    static final long MAX_TIMEOUT_SECONDS = 86400;

    private static final String ERR_UNAUTHENTICATED = "UNAUTHENTICATED";
    private static final String ERR_INVALID_SPEC = "INVALID_SPEC";
    private static final String ERR_CREDIT_INSUFFICIENT = "CREDIT_INSUFFICIENT";
    /** 与 02 同义的查询失败码；基准对象三查（存在/ACTIVE/可见）缺一才是 05 新码。 */
    private static final String ERR_TASK_NOT_FOUND = "TASK_NOT_FOUND";
    private static final String ERR_TARGET_NOT_FOUND = "MODIFICATION_TARGET_NOT_FOUND";
    private static final String ERR_FORBIDDEN_VISIBILITY = "FORBIDDEN_VISIBILITY";
    private static final String ERR_IN_PROGRESS = "MODIFICATION_IN_PROGRESS";
    private static final String ERR_LAUNCH_FAILED = "MODIFICATION_LAUNCH_FAILED";
    private static final String ERR_RUN_FAILED = "MODIFICATION_RUN_FAILED";
    private static final String ERR_TASK_TIMEOUT = "MODIFICATION_TASK_TIMEOUT";
    private static final String ERR_INTERNAL = "INTERNAL";

    private final AuthValidationClient authValidationClient;
    private final CreditClient creditClient;
    private final AgentRunClient agentRunClient;
    private final ModificationTaskMapper taskMapper;
    private final DataObjectRegistryService registryService;
    private final VisibilityGuard visibilityGuard;
    private final ModificationRegistrationService registrationService;

    public ModificationTaskServiceImpl(AuthValidationClient authValidationClient,
                                       CreditClient creditClient,
                                       AgentRunClient agentRunClient,
                                       ModificationTaskMapper taskMapper,
                                       DataObjectRegistryService registryService,
                                       VisibilityGuard visibilityGuard,
                                       ModificationRegistrationService registrationService) {
        this.authValidationClient = authValidationClient;
        this.creditClient = creditClient;
        this.agentRunClient = agentRunClient;
        this.taskMapper = taskMapper;
        this.registryService = registryService;
        this.visibilityGuard = visibilityGuard;
        this.registrationService = registrationService;
    }

    // ---- 请求/应答契约（无 proto，字段语义与 02 的 proto 消息一一对应） ----

    public record CreateModificationTaskRequest(String sessionToken, String specJson, String clientRequestId) {
    }

    /** error_code 非空时不透业务字段；拉起失败时任务行已落库，taskId/status 照 02 一并带出。 */
    public record CreateModificationTaskResponse(String taskId, String status, boolean deduplicated,
                                                 String errorCode, String errorMessage) {
    }

    public record GetModificationTaskRequest(String sessionToken, String taskId) {
    }

    /** query_error_code 非空表示查询本身失败；任务终态自身的 error_code 是任务资源本体信息。 */
    public record GetModificationTaskResponse(String queryErrorCode, String queryErrorMessage,
                                              String taskId, String status, String runId,
                                              Integer registeredVersion, Boolean columnsConfirmed,
                                              boolean incomplete, String errorCode, String errorMessage,
                                              String runStatusJson, int creditCharged, int creditSettled,
                                              long createdAtEpoch, long finishedAtEpoch) {
    }

    // ---- 受理 ----

    public CreateModificationTaskResponse createModificationTask(CreateModificationTaskRequest request) {
        // 第一跳：登录态校验。createRun 服务端不校验调用方身份，防伪造责任在本服务这一侧（同 02）。
        AuthValidationClient.AuthValidationResult auth = authValidationClient.validate(request.sessionToken());
        if (!auth.isValid()) {
            return createError(ERR_UNAUTHENTICATED, "session token invalid: " + auth.getMessage());
        }
        String userId = auth.getUserId();

        // 规格解析校验：02 的键全量沿用，另加 base_object_id / base_version / version_note 三键。
        if (request.clientRequestId() == null || request.clientRequestId().isBlank()) {
            return createError(ERR_INVALID_SPEC, "client_request_id is blank");
        }
        Struct spec;
        try {
            Struct.Builder builder = Struct.newBuilder();
            JsonFormat.parser().ignoringUnknownFields().merge(request.specJson(), builder);
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
        String baseObjectId = spec.getFieldsOrDefault("base_object_id", com.google.protobuf.Value.getDefaultInstance())
                .getStringValue();
        if (baseObjectId.isBlank()) {
            return createError(ERR_INVALID_SPEC, "spec_json.base_object_id is blank");
        }
        String versionNote = spec.getFieldsOrDefault("version_note", com.google.protobuf.Value.getDefaultInstance())
                .getStringValue();
        if (versionNote.isBlank()) {
            return createError(ERR_INVALID_SPEC,
                    "spec_json.version_note is blank（升版事由，与 instruction 语义不同，两个都要）");
        }
        Double baseVersionRaw = null;
        if (spec.getFieldsMap().containsKey("base_version")) {
            baseVersionRaw = spec.getFieldsOrThrow("base_version").getNumberValue();
            if (baseVersionRaw != Math.rint(baseVersionRaw) || baseVersionRaw < 1) {
                return createError(ERR_INVALID_SPEC, "spec_json.base_version must be a positive integer");
            }
        }
        // 可选 model 键：透传为 createRun 的 stage_config_json（同 02，测试尽量用便宜模型）。
        String stageConfigJson = null;
        if (spec.getFieldsMap().containsKey("model")) {
            String model = spec.getFieldsOrThrow("model").getStringValue();
            if (model.isBlank()) {
                return createError(ERR_INVALID_SPEC, "spec_json.model must be a non-blank string");
            }
            stageConfigJson = buildStageConfigJson(model);
        }

        // 基准对象校验：存在、状态 ACTIVE、对当前用户可见，三条缺一按可见性口径应答——
        // 存在性/状态走 05 新码 MODIFICATION_TARGET_NOT_FOUND，可见性走平台的 FORBIDDEN_VISIBILITY，
        // 两档分开是平台的既有映射口径（可见性经 VisibilityGuard，不自写属主比较，方案 D10）。
        DataObjectRegistryService.CardView card;
        try {
            card = registryService.get(baseObjectId);
        } catch (ObjectStoreExceptions.ObjectNotFoundException e) {
            return createError(ERR_TARGET_NOT_FOUND, "base object not found or not ACTIVE: " + baseObjectId);
        }
        if (!visibilityGuard.isVisible(card.object().owner(), userId)) {
            return createError(ERR_FORBIDDEN_VISIBILITY, "base object not visible to user: " + baseObjectId);
        }

        // 基准版本落定：受理时解析成具体版本号写入任务表，登记时只认这个号。
        // 显式给了 base_version 的，必须是受理时刻的当前有效版本——不是则受理即注定登记失败
        //（守卫在登记时拦截），白烧一次运行积分，不如受理时就拒绝；缺省取当前版本。
        int currentVersion = card.object().currentVersion();
        int baseVersion;
        if (baseVersionRaw != null) {
            baseVersion = (int) Math.rint(baseVersionRaw);
            if (baseVersion != currentVersion) {
                return createError(ERR_INVALID_SPEC,
                        "spec_json.base_version " + baseVersion + " is not the current active version "
                                + currentVersion + " of " + baseObjectId);
            }
        } else {
            baseVersion = currentVersion;
        }

        // 幂等去重（在余额校验之前）：命中直接返回已有任务，不新建也不重复计费（同 02）。
        ModificationTaskEntity existing = taskMapper.findByClientRequestId(userId, request.clientRequestId());
        if (existing != null) {
            return new CreateModificationTaskResponse(existing.getId(), existing.getStatus(), true, null, null);
        }

        // 余额校验：剩余积分需覆盖预扣额。读失败是后端故障，不映射成余额不足（同 02）。
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

        // 写任务表（ACCEPTED，规格快照）。互斥由 base_object_id 部分唯一索引承载。
        ModificationTaskEntity task = new ModificationTaskEntity();
        task.setId(UUID.randomUUID().toString());
        task.setUserId(userId);
        task.setBaseObjectId(baseObjectId);
        task.setBaseVersion(baseVersion);
        task.setVersionNote(versionNote);
        task.setSpecJson(request.specJson());
        task.setClientRequestId(request.clientRequestId());
        task.setStatus(ModificationTaskStateMachine.ACCEPTED);
        task.setCreditCharged(PRECHARGE_CREDITS);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            CreateModificationTaskResponse conflict = handleInsertConflict(task);
            if (conflict != null) {
                return conflict;
            }
        }

        // 预扣（本地直通期间只记录意图，字段照常流转，同 02）。
        creditClient.debit(userId, PRECHARGE_CREDITS, task.getId());

        // 拉起受控运行；幂等键填任务编号，防止重试造成重复运行。规格整体作为 contextJson
        // 注入（02 的三要素键 + 05 的三键一起进去，主仓库按约定键读）。
        try {
            AgentRunMessage run = agentRunClient.createRun(userId, instruction, request.specJson(),
                    task.getId(), stageConfigJson);
            taskMapper.updateRunning(task.getId(), ModificationTaskStateMachine.RUNNING, run.getId());
            return new CreateModificationTaskResponse(task.getId(), ModificationTaskStateMachine.RUNNING,
                    false, null, null);
        } catch (Exception e) {
            log.warn("createRun failed, task marked FAILED with full refund: task={} err={}", task.getId(), e.toString());
            finalizeTerminal(task, ModificationTaskStateMachine.FAILED, ERR_LAUNCH_FAILED,
                    "createRun failed: " + e.getMessage(), null, 0);
            // 任务行已落库（FAILED 且预扣全额回补），应答带上 task_id 方便调用方查询与核对（同 02）。
            return new CreateModificationTaskResponse(task.getId(), ModificationTaskStateMachine.FAILED,
                    false, ERR_LAUNCH_FAILED, "failed to launch agent run: " + e.getMessage());
        }
    }

    /** 组装 createRun 的 stage_config_json：与 02 同一构造（planning/execution 两阶段同模型，只覆盖模型名）。 */
    private String buildStageConfigJson(String model) {
        Struct.Builder stages = Struct.newBuilder();
        for (String stageName : new String[]{"planning", "execution"}) {
            stages.putFields(stageName, com.google.protobuf.Value.newBuilder()
                    .setStructValue(Struct.newBuilder()
                            .putFields("modelName", com.google.protobuf.Value.newBuilder()
                                    .setStringValue(model).build()))
                    .build());
        }
        try {
            return JsonFormat.printer().print(stages.build());
        } catch (Exception e) {
            // 构造内容全在本方法内、动态取值只有一个字符串，打印实际不会失败；防御性降级为无覆盖。
            log.warn("build stage_config_json failed, run uses server default model: err={}", e.toString());
            return null;
        }
    }

    /**
     * 插入冲突处理：先分辨去重竞态命中还是同对象互斥。互斥时对阻塞任务做一次惰性推进
     * （已终态或已超时则置终态释放互斥），再重试一次插入；仍冲突报 MODIFICATION_IN_PROGRESS。
     * 返回 null 表示重试插入已成功，调用方继续走预扣与拉起运行（同 02 形态）。
     */
    private CreateModificationTaskResponse handleInsertConflict(ModificationTaskEntity task) {
        ModificationTaskEntity dedupHit = taskMapper.findByClientRequestId(task.getUserId(), task.getClientRequestId());
        if (dedupHit != null) {
            return new CreateModificationTaskResponse(dedupHit.getId(), dedupHit.getStatus(), true, null, null);
        }
        ModificationTaskEntity blocker = taskMapper.findActiveByBaseObjectId(task.getBaseObjectId());
        if (blocker != null) {
            advanceBlockingTask(blocker);
        }
        try {
            taskMapper.insert(task);
            return null;
        } catch (DuplicateKeyException e2) {
            return createError(ERR_IN_PROGRESS,
                    "another modification task is active for base object " + task.getBaseObjectId());
        }
    }

    /**
     * 惰性推进阻塞任务：RUNNING 的读运行状态，运行已终态或超规格时限则置终态（成功则顺势
     * 触发产物登记）；ACCEPTED 的超拉起宽限则置失败终态（与查询路径共用同一段判定）。
     */
    private void advanceBlockingTask(ModificationTaskEntity blocker) {
        try {
            if (ModificationTaskStateMachine.RUNNING.equals(blocker.getStatus()) && blocker.getRunId() != null) {
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
     * 置 FAILED（MODIFICATION_LAUNCH_FAILED）并全额回补预扣（同 02）。
     */
    private void advanceAcceptedIfStale(ModificationTaskEntity task) {
        if (!ModificationTaskStateMachine.ACCEPTED.equals(task.getStatus())
                || task.getCreatedAt() == null
                || task.getCreatedAt().plusSeconds(LAUNCH_GRACE_SECONDS).isAfter(ModificationTaskStateMachine.nowUtc())) {
            return;
        }
        finalizeTerminal(task, ModificationTaskStateMachine.FAILED, ERR_LAUNCH_FAILED,
                "accepted but never launched within grace " + LAUNCH_GRACE_SECONDS + "s", null, 0);
    }

    // ---- 查询 ----

    public GetModificationTaskResponse getModificationTask(GetModificationTaskRequest request) {
        AuthValidationClient.AuthValidationResult auth = authValidationClient.validate(request.sessionToken());
        if (!auth.isValid()) {
            return getQueryError(ERR_UNAUTHENTICATED, "session token invalid: " + auth.getMessage());
        }
        ModificationTaskEntity task = taskMapper.findById(request.taskId());
        // 属主校验：任务只对本主可见，查别人的任务与查不存在的任务同一应答（同 02 口径）。
        if (task == null || !auth.getUserId().equals(task.getUserId())) {
            return getQueryError(ERR_TASK_NOT_FOUND, "task not found: " + request.taskId());
        }

        // 非终态任务做惰性推进：ACCEPTED 超宽限置失败终态；RUNNING 读运行实态，运行已终态
        // 或超规格时限则置终态并结算，成功置 SUCCEEDED 后顺势触发产物登记（05 与 02 的差异点）。
        String runStatusJson = "";
        if (!ModificationTaskStateMachine.isTerminal(task.getStatus())) {
            if (ModificationTaskStateMachine.RUNNING.equals(task.getStatus()) && task.getRunId() != null) {
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

        return new GetModificationTaskResponse(
                null, null,
                task.getId(), task.getStatus(),
                task.getRunId() == null ? "" : task.getRunId(),
                task.getRegisteredVersion(),
                Boolean.TRUE.equals(task.getColumnsConfirmed()),
                Boolean.TRUE.equals(task.getIncomplete()),
                task.getErrorCode() == null ? "" : task.getErrorCode(),
                task.getErrorMessage() == null ? "" : task.getErrorMessage(),
                runStatusJson,
                task.getCreditCharged() == null ? 0 : task.getCreditCharged(),
                task.getCreditSettled() == null ? 0 : task.getCreditSettled(),
                task.getCreatedAt() == null ? 0 : task.getCreatedAt().toEpochSecond(),
                task.getFinishedAt() == null ? 0 : task.getFinishedAt().toEpochSecond());
    }

    // ---- 终态与结算 ----

    /**
     * 按运行实态给 RUNNING 任务置终态并结算（三档与 02 同型同值）：运行成功（COMPLETED/PARTIAL）
     * 实结 = 已消耗 + 附加费，随后触发产物登记；运行失败按已消耗实结；超规格时限置
     * MODIFICATION_TASK_TIMEOUT，按状态摘要里已消耗实结，并尽力取消运行。
     * 已消耗的权威源是 getStatus 返回的 total_credits_consumed（同 02）。
     */
    private void settleByRunStatus(ModificationTaskEntity task, AgentRunStatusMessage runStatus) {
        int consumed = runStatus.getTotalCreditsConsumed();
        String runState = runStatus.getStatus();
        if ("COMPLETED".equals(runState) || "PARTIAL".equals(runState)) {
            boolean settled = finalizeTerminal(task, ModificationTaskStateMachine.SUCCEEDED,
                    null, null, runStatus, consumed + SURCHARGE_CREDITS);
            if (settled) {
                // 任务置 SUCCEEDED 后执行产物登记（方案 D1）：取回 CSV → 三样最小校验 →
                // 基准版本守卫 → CreateNewVersion。失败在登记段内部改判 FAILED_INCOMPLETE。
                registrationService.registerOutcome(task.getId());
            }
        } else if ("FAILED".equals(runState) || "CANCELED".equals(runState) || "EXPIRED".equals(runState)) {
            finalizeTerminal(task, ModificationTaskStateMachine.FAILED, ERR_RUN_FAILED,
                    "agent run terminated with status " + runState, runStatus, consumed);
        } else if (isTaskTimedOut(task)) {
            // 先取消运行再置终态：任务置终后平台不再结算，不能让运行继续烧积分（同 02）。
            try {
                agentRunClient.cancelRun(task.getUserId(), task.getRunId());
            } catch (Exception e) {
                log.warn("cancelRun on timeout failed (task finalization proceeds): task={} run={} err={}",
                        task.getId(), task.getRunId(), e.toString());
            }
            finalizeTerminal(task, ModificationTaskStateMachine.FAILED, ERR_TASK_TIMEOUT,
                    "run exceeded spec timeout", runStatus, consumed);
        }
    }

    /**
     * 写终态并记录结算意图；回补金额 = 预扣 - 实结，由 CreditClient 结算时一并记录（同 02）。
     * updateTerminal 带非终态守卫，并发惰性推进只有一个能写上；写不上的跳过结算并返回 false，
     * 防止重复 settle，也防止登记段被并发重复触发。
     */
    private boolean finalizeTerminal(ModificationTaskEntity task, String status, String errorCode, String errorMessage,
                                     AgentRunStatusMessage runStatus, int settledCredits) {
        if (!ModificationTaskStateMachine.canTransition(task.getStatus(), status)) {
            log.warn("illegal transition skipped: task={} from={} to={}", task.getId(), task.getStatus(), status);
            return false;
        }
        task.setStatus(status);
        if (runStatus != null) {
            task.setRunId(runStatus.getId());
        }
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);
        task.setCreditSettled(settledCredits);
        task.setFinishedAt(ModificationTaskStateMachine.nowUtc());
        int updated = taskMapper.updateTerminal(task);
        if (updated == 0) {
            log.info("terminal update lost to concurrent finalization, settle skipped: task={} to={}", task.getId(), status);
            return false;
        }
        creditClient.settle(task.getUserId(),
                task.getCreditCharged() == null ? 0 : task.getCreditCharged(), settledCredits, task.getId());
        return true;
    }

    private boolean isRunTerminal(String runStatus) {
        return "COMPLETED".equals(runStatus) || "PARTIAL".equals(runStatus)
                || "FAILED".equals(runStatus) || "CANCELED".equals(runStatus) || "EXPIRED".equals(runStatus);
    }

    /** 惰性超时判定所需的规格时限从任务表的规格快照读；快照在受理时已校验，解析失败按未超时处理（同 02）。 */
    private boolean isTaskTimedOut(ModificationTaskEntity task) {
        try {
            Struct.Builder builder = Struct.newBuilder();
            JsonFormat.parser().ignoringUnknownFields().merge(task.getSpecJson(), builder);
            long timeoutSeconds = (long) builder.getFieldsOrDefault("timeout_seconds",
                    com.google.protobuf.Value.getDefaultInstance()).getNumberValue();
            return ModificationTaskStateMachine.isTimedOut(task, timeoutSeconds, ModificationTaskStateMachine.nowUtc());
        } catch (Exception e) {
            return false;
        }
    }

    private static CreateModificationTaskResponse createError(String code, String message) {
        return new CreateModificationTaskResponse(null, null, false, code, message);
    }

    private static GetModificationTaskResponse getQueryError(String code, String message) {
        return new GetModificationTaskResponse(code, message, null, null, null, null, null,
                false, null, null, "", 0, 0, 0, 0);
    }
}
