package world.willfrog.datafrog.server.objectdata.repo;

/** datafrog_object_version 表行载体；part_checksums 以 JSON 字符串进出。
 *  末尾连续完整日两列（014-data-04）可空：建列与查询归 04，写入归 03（审核批准完成登记或追加后写入）。 */
public record DataObjectVersionRow(
        String objectId,
        int version,
        String versionNote,
        Integer prevVersion,
        String coverageStart,
        String contentPath,
        int partCount,
        String partChecksumsJson,
        long rowCount,
        String coverageEnd,
        String contentChecksum,
        String continuousCompleteDate,
        Boolean hasGapAfterWatermark,
        String status,
        long createdAt,
        long watermarkUpdatedAt,
        // 内容物理清理时刻（014-data-05 方案 D6 增列）：null = 内容文件仍在；行本身永不物理删。
        Long contentPurgedAt
) {
}
