package world.willfrog.datafrog.server.objectdata.repo;

/** datafrog_semantic_definition 表行载体。 */
public record SemanticDefinitionRow(
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
