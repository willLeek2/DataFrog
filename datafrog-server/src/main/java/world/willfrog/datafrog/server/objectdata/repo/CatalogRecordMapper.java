package world.willfrog.datafrog.server.objectdata.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 目录台账：只增不改（规范篇第 3 节，登记/追加/修改/删除全部落账，供审计与回溯）。 */
@Mapper
public interface CatalogRecordMapper {

    @Insert("""
            INSERT INTO datafrog_catalog_record (object_id, action, actor, summary, created_at)
            VALUES (#{objectId}, #{action}, #{actor}, #{summary}, #{createdAt})
            """)
    int insert(@Param("objectId") String objectId, @Param("action") String action,
               @Param("actor") String actor, @Param("summary") String summary,
               @Param("createdAt") long createdAt);

    @Select("""
            SELECT id AS "id", object_id AS "objectId", action AS "action", actor AS "actor",
                summary AS "summary", created_at AS "createdAt"
            FROM datafrog_catalog_record WHERE object_id = #{objectId} ORDER BY id LIMIT #{limit}
            """)
    java.util.List<CatalogRecordRow> listByObject(@Param("objectId") String objectId,
                                                  @Param("limit") int limit);
}
