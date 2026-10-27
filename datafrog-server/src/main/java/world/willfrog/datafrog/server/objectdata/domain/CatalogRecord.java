package world.willfrog.datafrog.server.objectdata.domain;

/** 目录台账行（规范篇第 3 节【已定】：元数据在生成那一刻登记进目录，修改与删除也走目录记录）。 */
public record CatalogRecord(
        long id,
        String objectId,
        CatalogAction action,
        String actor,
        String summary,
        long createdAt
) {
}
