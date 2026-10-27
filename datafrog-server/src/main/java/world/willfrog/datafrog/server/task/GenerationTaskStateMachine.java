package world.willfrog.datafrog.server.task;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 生成任务状态机（014-data-02），纯逻辑类，不依赖 Spring 与数据库。
 * 负责两件事：状态迁移合法性；惰性超时判定——不设后台巡检，
 * 任务是否超时只在查询时与互斥冲突处理时检查（方案 v2 的取舍，经 wang 审查共识）。
 */
public final class GenerationTaskStateMachine {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    // FAILED_INCOMPLETE 本波不由 02 置位：保留成功部分并标记不完整的正文在 014-data-03，
    // 产物记录在 03 落地前不存在，该状态届时由 03 的产物保留逻辑写入。
    public static final String FAILED_INCOMPLETE = "FAILED_INCOMPLETE";

    private GenerationTaskStateMachine() {
    }

    public static boolean isTerminal(String status) {
        return SUCCEEDED.equals(status) || FAILED.equals(status) || FAILED_INCOMPLETE.equals(status);
    }

    public static boolean isActive(String status) {
        return ACCEPTED.equals(status) || RUNNING.equals(status);
    }

    /** ACCEPTED 只允许走向 RUNNING（拉起成功）或 FAILED（拉起失败）。 */
    public static boolean canTransition(String from, String to) {
        if (ACCEPTED.equals(from)) {
            return RUNNING.equals(to) || FAILED.equals(to);
        }
        if (RUNNING.equals(from)) {
            return SUCCEEDED.equals(to) || FAILED.equals(to) || FAILED_INCOMPLETE.equals(to);
        }
        return false;
    }

    /** 惰性超时判定：任务处于 RUNNING 且当前时刻已超过创建时刻加规格时限。 */
    public static boolean isTimedOut(GenerationTaskEntity task, long timeoutSeconds, OffsetDateTime nowUtc) {
        if (!RUNNING.equals(task.getStatus()) || task.getCreatedAt() == null) {
            return false;
        }
        OffsetDateTime deadline = task.getCreatedAt().plusSeconds(timeoutSeconds);
        return !nowUtc.isBefore(deadline);
    }

    public static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
