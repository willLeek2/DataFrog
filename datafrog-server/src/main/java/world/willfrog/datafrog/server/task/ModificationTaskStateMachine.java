package world.willfrog.datafrog.server.task;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 修改任务状态机（014-data-05），纯逻辑类，不依赖 Spring 与数据库。
 * 状态集与迁移合法性镜像 GenerationTaskStateMachine；差异一处：SUCCEEDED 允许改判
 * FAILED_INCOMPLETE——修改任务的产物登记在任务置 SUCCEEDED（运行成功、已结算）之后由
 * 登记段执行（方案 D1），登记失败时改判 FAILED_INCOMPLETE，02 里这个状态的写入方是
 * 03 的产物保留逻辑，05 里是本任务的登记段（ModificationRegistrationService）。
 *
 * 惰性超时判定同样不设后台巡检：任务是否超时只在查询时与互斥冲突处理时检查。
 */
public final class ModificationTaskStateMachine {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    /** 登记段失败（CSV 校验不过、基准版本已被替代、升版失败）由登记段写入。 */
    public static final String FAILED_INCOMPLETE = "FAILED_INCOMPLETE";

    private ModificationTaskStateMachine() {
    }

    public static boolean isTerminal(String status) {
        return SUCCEEDED.equals(status) || FAILED.equals(status) || FAILED_INCOMPLETE.equals(status);
    }

    public static boolean isActive(String status) {
        return ACCEPTED.equals(status) || RUNNING.equals(status);
    }

    /**
     * ACCEPTED 只允许走向 RUNNING（拉起成功）或 FAILED（拉起失败）；
     * RUNNING 允许走向 SUCCEEDED / FAILED / FAILED_INCOMPLETE；
     * SUCCEEDED 只允许改判 FAILED_INCOMPLETE（登记段失败），且改判后是终态不再迁移。
     */
    public static boolean canTransition(String from, String to) {
        if (ACCEPTED.equals(from)) {
            return RUNNING.equals(to) || FAILED.equals(to);
        }
        if (RUNNING.equals(from)) {
            return SUCCEEDED.equals(to) || FAILED.equals(to) || FAILED_INCOMPLETE.equals(to);
        }
        if (SUCCEEDED.equals(from)) {
            return FAILED_INCOMPLETE.equals(to);
        }
        return false;
    }

    /** 惰性超时判定：任务处于 RUNNING 且当前时刻已超过创建时刻加规格时限。 */
    public static boolean isTimedOut(ModificationTaskEntity task, long timeoutSeconds, OffsetDateTime nowUtc) {
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
