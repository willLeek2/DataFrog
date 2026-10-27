package world.willfrog.datafrog.server.objectdata.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 数据对象行（声明与身份类字段）。数据覆盖四项不在本表（唯一来源=版本行）。
 * semantic_refs / inputs / primary_key / assertions 以 JSON 字符串写入，SQL 内显式转 JSONB。
 */
@Mapper
public interface DataObjectMapper {

    /** 枚举过滤条件（014-data-04）：对象行条件四件（含属主等值，bob 节点 c 裁定）+ ACTIVE 版本行
     *  覆盖条件三件；断言状态过滤不在 SQL。全部留空时 rpc 层直接走 listActive（01 纯分页行为不动）。 */
    record ListFilters(String semanticName, String fieldName, String coverageScopeSubstring,
                       String coverageStartFrom, String coverageEndTo, String freshnessSince,
                       String owner) {

        /** 任一覆盖条件非空时需要 JOIN ACTIVE 版本行下推。 */
        public boolean joinVersion() {
            return notBlank(coverageStartFrom) || notBlank(coverageEndTo) || notBlank(freshnessSince);
        }

        /** 全部条件留空。 */
        public boolean isEmpty() {
            return !notBlank(semanticName) && !notBlank(fieldName) && !notBlank(coverageScopeSubstring)
                    && !notBlank(owner) && !joinVersion();
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    @Insert("""
            INSERT INTO datafrog_data_object (id, name, description, source_type, owner, visibility,
                semantic_refs, coverage_scope, frequency, gen_method, gen_task, inputs, primary_key,
                assertions, adjustment, ccy, current_version, export_format, compression, status,
                created_at, updated_at, retention_days, version_keep_last)
            VALUES (#{o.id}, #{o.name}, #{o.description}, #{o.sourceType}, #{o.owner}, #{o.visibility},
                CAST(#{o.semanticRefsJson} AS JSONB), #{o.coverageScope}, #{o.frequency}, #{o.genMethod},
                #{o.genTask}, CAST(#{o.inputsJson} AS JSONB), CAST(#{o.primaryKeyJson} AS JSONB),
                CAST(#{o.assertionsJson} AS JSONB), #{o.adjustment}, #{o.ccy}, #{o.currentVersion},
                #{o.exportFormat}, #{o.compression}, #{o.status}, #{o.createdAt}, #{o.updatedAt},
                #{o.retentionDays}, #{o.versionKeepLast})
            """)
    int insert(@Param("o") DataObjectRow o);

    @Select("""
            SELECT id AS "id", name AS "name", description AS "description", source_type AS "sourceType",
                owner AS "owner", visibility AS "visibility",
                semantic_refs::text AS "semanticRefsJson", coverage_scope AS "coverageScope",
                frequency AS "frequency", gen_method AS "genMethod", gen_task AS "genTask",
                inputs::text AS "inputsJson", primary_key::text AS "primaryKeyJson",
                assertions::text AS "assertionsJson",
                adjustment AS "adjustment", ccy AS "ccy", current_version AS "currentVersion",
                export_format AS "exportFormat", compression AS "compression", status AS "status",
                created_at AS "createdAt", updated_at AS "updatedAt",
                retention_days AS "retentionDays", version_keep_last AS "versionKeepLast",
                deleted_at AS "deletedAt"
            FROM datafrog_data_object WHERE id = #{id}
            """)
    DataObjectRow getById(@Param("id") String id);

    /** 软删（保留策略任务前不物理删；规范篇拍板 6 保留策略联动生命周期）。deleted_at 记软删时刻。 */
    @Update("UPDATE datafrog_data_object SET status = 'DELETED', deleted_at = #{now}, updated_at = #{now} WHERE id = #{id}")
    int markDeleted(@Param("id") String id, @Param("now") long now);

    @Update("UPDATE datafrog_data_object SET current_version = #{version}, updated_at = #{now} WHERE id = #{id}")
    int updateCurrentVersion(@Param("id") String id, @Param("version") int version, @Param("now") long now);

    @Select("""
            SELECT id AS "id", name AS "name", description AS "description", source_type AS "sourceType",
                owner AS "owner", visibility AS "visibility",
                semantic_refs::text AS "semanticRefsJson", coverage_scope AS "coverageScope",
                frequency AS "frequency", gen_method AS "genMethod", gen_task AS "genTask",
                inputs::text AS "inputsJson", primary_key::text AS "primaryKeyJson",
                assertions::text AS "assertionsJson",
                adjustment AS "adjustment", ccy AS "ccy", current_version AS "currentVersion",
                export_format AS "exportFormat", compression AS "compression", status AS "status",
                created_at AS "createdAt", updated_at AS "updatedAt",
                retention_days AS "retentionDays", version_keep_last AS "versionKeepLast",
                deleted_at AS "deletedAt"
            FROM datafrog_data_object WHERE status = 'ACTIVE' ORDER BY id LIMIT #{limit} OFFSET #{offset}
            """)
    java.util.List<DataObjectRow> listActive(@Param("limit") int limit, @Param("offset") int offset);

    /**
     * 过滤枚举（014-data-04，动态 WHERE）。数据域/字段两条件走 EXISTS jsonb_array_elements(semantic_refs)
     * 取 name（挂靠的语义定义本身就是字段级语义单元，定义表没有独立字段列）；覆盖条件三件经
     * JOIN datafrog_object_version(status='ACTIVE') 下推（01 已建 partial index）。
     * 断言状态过滤不在 SQL（结论现算不落库，归 service 两段式）：调用方带断言过滤时 limit 传 null，
     * 本方法就不带 LIMIT/OFFSET、候选按 id 序全量取出；无断言过滤时 limit 非 null，SQL 直出分页。
     */
    @Select("""
            <script>
            SELECT o.id AS "id", o.name AS "name", o.description AS "description", o.source_type AS "sourceType",
                o.owner AS "owner", o.visibility AS "visibility",
                o.semantic_refs::text AS "semanticRefsJson", o.coverage_scope AS "coverageScope",
                o.frequency AS "frequency", o.gen_method AS "genMethod", o.gen_task AS "genTask",
                o.inputs::text AS "inputsJson", o.primary_key::text AS "primaryKeyJson",
                o.assertions::text AS "assertionsJson",
                o.adjustment AS "adjustment", o.ccy AS "ccy", o.current_version AS "currentVersion",
                o.export_format AS "exportFormat", o.compression AS "compression", o.status AS "status",
                o.created_at AS "createdAt", o.updated_at AS "updatedAt",
                o.retention_days AS "retentionDays", o.version_keep_last AS "versionKeepLast",
                o.deleted_at AS "deletedAt"
            FROM datafrog_data_object o
            <if test="f.joinVersion">
                JOIN datafrog_object_version v ON v.object_id = o.id AND v.status = 'ACTIVE'
            </if>
            WHERE o.status = 'ACTIVE'
            <if test="f.owner != null and f.owner != ''">
                AND o.owner = #{f.owner}
            </if>
            <if test="f.semanticName != null and f.semanticName != ''">
                AND EXISTS (SELECT 1 FROM jsonb_array_elements(o.semantic_refs) r
                    WHERE r-&gt;&gt;'name' LIKE #{f.semanticName} || '%')
            </if>
            <if test="f.fieldName != null and f.fieldName != ''">
                AND EXISTS (SELECT 1 FROM jsonb_array_elements(o.semantic_refs) r
                    WHERE r-&gt;&gt;'name' = #{f.fieldName})
            </if>
            <if test="f.coverageScopeSubstring != null and f.coverageScopeSubstring != ''">
                AND o.coverage_scope LIKE '%' || #{f.coverageScopeSubstring} || '%'
            </if>
            <if test="f.coverageStartFrom != null and f.coverageStartFrom != ''">
                AND v.coverage_start &gt;= #{f.coverageStartFrom}
            </if>
            <if test="f.coverageEndTo != null and f.coverageEndTo != ''">
                AND v.coverage_end &lt;= #{f.coverageEndTo}
            </if>
            <if test="f.freshnessSince != null and f.freshnessSince != ''">
                AND v.coverage_end &gt;= #{f.freshnessSince}
            </if>
            ORDER BY o.id
            <if test="limit != null">
                LIMIT #{limit} OFFSET #{offset}
            </if>
            </script>
            """)
    java.util.List<DataObjectRow> listFiltered(@Param("f") ListFilters f, @Param("limit") Integer limit,
                                               @Param("offset") int offset);

    /** 与 {@link #listFiltered} 同一组过滤条件的计数（无断言过滤路径算 next_page_token 用）。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM datafrog_data_object o
            <if test="f.joinVersion">
                JOIN datafrog_object_version v ON v.object_id = o.id AND v.status = 'ACTIVE'
            </if>
            WHERE o.status = 'ACTIVE'
            <if test="f.owner != null and f.owner != ''">
                AND o.owner = #{f.owner}
            </if>
            <if test="f.semanticName != null and f.semanticName != ''">
                AND EXISTS (SELECT 1 FROM jsonb_array_elements(o.semantic_refs) r
                    WHERE r-&gt;&gt;'name' LIKE #{f.semanticName} || '%')
            </if>
            <if test="f.fieldName != null and f.fieldName != ''">
                AND EXISTS (SELECT 1 FROM jsonb_array_elements(o.semantic_refs) r
                    WHERE r-&gt;&gt;'name' = #{f.fieldName})
            </if>
            <if test="f.coverageScopeSubstring != null and f.coverageScopeSubstring != ''">
                AND o.coverage_scope LIKE '%' || #{f.coverageScopeSubstring} || '%'
            </if>
            <if test="f.coverageStartFrom != null and f.coverageStartFrom != ''">
                AND v.coverage_start &gt;= #{f.coverageStartFrom}
            </if>
            <if test="f.coverageEndTo != null and f.coverageEndTo != ''">
                AND v.coverage_end &lt;= #{f.coverageEndTo}
            </if>
            <if test="f.freshnessSince != null and f.freshnessSince != ''">
                AND v.coverage_end &gt;= #{f.freshnessSince}
            </if>
            </script>
            """)
    long countFiltered(@Param("f") ListFilters f);

    /** 软删对象分页（014-data-05 清理执行体扫描到期候选用）。 */
    @Select("""
            SELECT id AS "id", name AS "name", description AS "description", source_type AS "sourceType",
                owner AS "owner", visibility AS "visibility",
                semantic_refs::text AS "semanticRefsJson", coverage_scope AS "coverageScope",
                frequency AS "frequency", gen_method AS "genMethod", gen_task AS "genTask",
                inputs::text AS "inputsJson", primary_key::text AS "primaryKeyJson",
                assertions::text AS "assertionsJson",
                adjustment AS "adjustment", ccy AS "ccy", current_version AS "currentVersion",
                export_format AS "exportFormat", compression AS "compression", status AS "status",
                created_at AS "createdAt", updated_at AS "updatedAt",
                retention_days AS "retentionDays", version_keep_last AS "versionKeepLast",
                deleted_at AS "deletedAt"
            FROM datafrog_data_object WHERE status = 'DELETED' ORDER BY id LIMIT #{limit} OFFSET #{offset}
            """)
    java.util.List<DataObjectRow> listDeleted(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM datafrog_data_object WHERE status = 'ACTIVE'")
    long countActive();

    /** 按对象 id 集合批量取行（014-data-05 语义查询的回表拼接用）。 */
    @Select("""
            <script>
            SELECT id AS "id", name AS "name", description AS "description", source_type AS "sourceType",
                owner AS "owner", visibility AS "visibility",
                semantic_refs::text AS "semanticRefsJson", coverage_scope AS "coverageScope",
                frequency AS "frequency", gen_method AS "genMethod", gen_task AS "genTask",
                inputs::text AS "inputsJson", primary_key::text AS "primaryKeyJson",
                assertions::text AS "assertionsJson",
                adjustment AS "adjustment", ccy AS "ccy", current_version AS "currentVersion",
                export_format AS "exportFormat", compression AS "compression", status AS "status",
                created_at AS "createdAt", updated_at AS "updatedAt",
                retention_days AS "retentionDays", version_keep_last AS "versionKeepLast",
                deleted_at AS "deletedAt"
            FROM datafrog_data_object WHERE id IN
            <foreach collection="ids" item="item" open="(" separator="," close=")">#{item}</foreach>
            </script>
            """)
    java.util.List<DataObjectRow> getByIds(@Param("ids") java.util.List<String> ids);

    /** 表存在性检查（建表脚本可重复执行；缺失=部署链路没跑 DDL，加载器据此 fail-fast）。 */
    @Select("SELECT to_regclass('datafrog_data_object') IS NOT NULL")
    boolean tableExists();
}
