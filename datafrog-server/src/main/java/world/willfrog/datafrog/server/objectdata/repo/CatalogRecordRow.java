package world.willfrog.datafrog.server.objectdata.repo;

/** datafrog_catalog_record 表行载体。 */
public record CatalogRecordRow(
        long id,
        String objectId,
        String action,
        String actor,
        String summary,
        long createdAt
) {
}
