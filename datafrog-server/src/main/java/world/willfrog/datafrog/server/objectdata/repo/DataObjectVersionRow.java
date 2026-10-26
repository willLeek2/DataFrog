package world.willfrog.datafrog.server.objectdata.repo;

/** datafrog_object_version 表行载体；part_checksums 以 JSON 字符串进出。 */
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
        String status,
        long createdAt,
        long watermarkUpdatedAt
) {
}
