package world.willfrog.datafrog.server.maintenance;

/**
 * 导出触发被限额拒绝（014-data-05 方案 D8）：每用户每日次数超限，或同一对象仍在
 * 冷却间隔内。HTTP 适配层捕获后映射 429 RATE_LIMITED；message 面向调用方可读。
 */
public class ExportRateLimitedException extends RuntimeException {

    public ExportRateLimitedException(String message) {
        super(message);
    }
}
