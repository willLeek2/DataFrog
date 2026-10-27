package world.willfrog.datafrog.server.objectdata.repo;

/**
 * datafrog_data_object 表行的平铺载体：四个集合列以 JSON 字符串进出（SQL 内显式转 JSONB），
 * 由服务层负责与领域对象的互转。assertions（014-data-04）同此口径，照 semantic_refs 的现成写法。
 */
public record DataObjectRow(
        String id,
        String name,
        String description,
        String sourceType,
        String owner,
        String visibility,
        String semanticRefsJson,
        String coverageScope,
        String frequency,
        String genMethod,
        String genTask,
        String inputsJson,
        String primaryKeyJson,
        String assertionsJson,
        String adjustment,
        String ccy,
        int currentVersion,
        String exportFormat,
        String compression,
        String status,
        long createdAt,
        long updatedAt
) {
}
