package world.willfrog.datafrog.server.objectdata.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 数据对象行（声明与身份类字段）。水位与覆盖四项不在本表（唯一来源=版本行）。
 * semantic_refs / inputs / primary_key 以 JSON 字符串写入，SQL 内显式转 JSONB。
 */
@Mapper
public interface DataObjectMapper {

    @Insert("""
            INSERT INTO datafrog_data_object (id, name, description, source_type, owner, visibility,
                semantic_refs, coverage_scope, frequency, gen_method, gen_task, inputs, primary_key,
                adjustment, ccy, current_version, export_format, compression, status, created_at, updated_at)
            VALUES (#{o.id}, #{o.name}, #{o.description}, #{o.sourceType}, #{o.owner}, #{o.visibility},
                CAST(#{o.semanticRefsJson} AS JSONB), #{o.coverageScope}, #{o.frequency}, #{o.genMethod},
                #{o.genTask}, CAST(#{o.inputsJson} AS JSONB), CAST(#{o.primaryKeyJson} AS JSONB),
                #{o.adjustment}, #{o.ccy}, #{o.currentVersion}, #{o.exportFormat}, #{o.compression},
                #{o.status}, #{o.createdAt}, #{o.updatedAt})
            """)
    int insert(@Param("o") DataObjectRow o);

    @Select("""
            SELECT id AS "id", name AS "name", description AS "description", source_type AS "sourceType",
                owner AS "owner", visibility AS "visibility",
                semantic_refs::text AS "semanticRefsJson", coverage_scope AS "coverageScope",
                frequency AS "frequency", gen_method AS "genMethod", gen_task AS "genTask",
                inputs::text AS "inputsJson", primary_key::text AS "primaryKeyJson",
                adjustment AS "adjustment", ccy AS "ccy", current_version AS "currentVersion",
                export_format AS "exportFormat", compression AS "compression", status AS "status",
                created_at AS "createdAt", updated_at AS "updatedAt"
            FROM datafrog_data_object WHERE id = #{id}
            """)
    DataObjectRow getById(@Param("id") String id);

    /** 软删（保留策略任务前不物理删；规范篇拍板 6 保留策略联动生命周期）。 */
    @Update("UPDATE datafrog_data_object SET status = 'DELETED', updated_at = #{now} WHERE id = #{id}")
    int markDeleted(@Param("id") String id, @Param("now") long now);

    @Update("UPDATE datafrog_data_object SET current_version = #{version}, updated_at = #{now} WHERE id = #{id}")
    int updateCurrentVersion(@Param("id") String id, @Param("version") int version, @Param("now") long now);

    @Select("""
            SELECT id AS "id", name AS "name", description AS "description", source_type AS "sourceType",
                owner AS "owner", visibility AS "visibility",
                semantic_refs::text AS "semanticRefsJson", coverage_scope AS "coverageScope",
                frequency AS "frequency", gen_method AS "genMethod", gen_task AS "genTask",
                inputs::text AS "inputsJson", primary_key::text AS "primaryKeyJson",
                adjustment AS "adjustment", ccy AS "ccy", current_version AS "currentVersion",
                export_format AS "exportFormat", compression AS "compression", status AS "status",
                created_at AS "createdAt", updated_at AS "updatedAt"
            FROM datafrog_data_object WHERE status = 'ACTIVE' ORDER BY id LIMIT #{limit} OFFSET #{offset}
            """)
    java.util.List<DataObjectRow> listActive(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM datafrog_data_object WHERE status = 'ACTIVE'")
    long countActive();

    /** 表存在性检查（建表脚本可重复执行；缺失=部署链路没跑 DDL，加载器据此 fail-fast）。 */
    @Select("SELECT to_regclass('datafrog_data_object') IS NOT NULL")
    boolean tableExists();
}
