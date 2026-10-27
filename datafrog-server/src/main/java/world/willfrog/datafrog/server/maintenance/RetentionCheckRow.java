package world.willfrog.datafrog.server.maintenance;

/** datafrog_retention_check_record 表行载体（014-data-05 方案 D7）。 */
public record RetentionCheckRow(
        long id,
        String objectId,
        String checkType,
        String expected,
        String actual,
        String verdict,
        String detail,
        long checkedAt
) {
}
