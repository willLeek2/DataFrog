package world.willfrog.datafrog.server.objectdata.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 语义定义表。(name, version) 主键：重复注册由唯一约束拦下，服务层先查先报友好错误码。 */
@Mapper
public interface SemanticDefinitionMapper {

    @Insert("""
            INSERT INTO datafrog_semantic_definition (name, version, display_name, definition, calculation,
                unit, scope, status, origin, created_at)
            VALUES (#{name}, #{version}, #{displayName}, #{definition}, #{calculation},
                #{unit}, #{scope}, 'ACTIVE', #{origin}, #{createdAt})
            """)
    int insert(@Param("name") String name, @Param("version") int version, @Param("displayName") String displayName,
               @Param("definition") String definition, @Param("calculation") String calculation,
               @Param("unit") String unit, @Param("scope") String scope, @Param("origin") String origin,
               @Param("createdAt") long createdAt);

    @Select("""
            SELECT name AS "name", version AS "version", display_name AS "displayName",
                definition AS "definition", calculation AS "calculation", unit AS "unit",
                scope AS "scope", status AS "status", origin AS "origin", created_at AS "createdAt"
            FROM datafrog_semantic_definition WHERE name = #{name} AND version = #{version}
            """)
    SemanticDefinitionRow getByNameVersion(@Param("name") String name, @Param("version") int version);

    @Select("""
            SELECT name AS "name", version AS "version", display_name AS "displayName",
                definition AS "definition", calculation AS "calculation", unit AS "unit",
                scope AS "scope", status AS "status", origin AS "origin", created_at AS "createdAt"
            FROM datafrog_semantic_definition ORDER BY name, version LIMIT #{limit} OFFSET #{offset}
            """)
    java.util.List<SemanticDefinitionRow> list(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM datafrog_semantic_definition")
    long count();

    @Select("SELECT to_regclass('datafrog_semantic_definition') IS NOT NULL")
    boolean tableExists();
}
