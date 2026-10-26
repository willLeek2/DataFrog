package world.willfrog.datafrog.server.objectdata.domain;

/**
 * 语义定义（规范篇第 2 节【已定】：每个指标、每个字段集中定义，写明名称、自然语言定义、
 * 计算方式、单位与适用范围；生成与修改数据对象必须挂靠已有定义）。
 * origin=migrated 表示迁移自 01304 方法目录，manual 表示平台登记。
 */
public record SemanticDefinition(
        String name,
        int version,
        String displayName,
        String definition,
        String calculation,
        String unit,
        String scope,
        String status,
        String origin,
        long createdAt
) {
}
