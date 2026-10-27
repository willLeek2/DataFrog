package world.willfrog.datafrog.server.maintenance;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 留存与清理的执行核验记录（014-data-05 方案 D7）。表 datafrog_retention_check_record
 * （DDL 见 db/datafrog-objectstore-v3.sql）。写入口只有 RetentionCheckRecorder；
 * 查询面经维护端点 GET /maintenance/retention-checks 按对象与裁定结果过滤。
 */
@Mapper
public interface RetentionCheckMapper {

    @Insert("INSERT INTO datafrog_retention_check_record "
            + "(object_id, check_type, expected, actual, verdict, detail, checked_at) "
            + "VALUES (#{objectId}, #{checkType}, #{expected}, #{actual}, #{verdict}, #{detail}, #{now})")
    int insert(@Param("objectId") String objectId, @Param("checkType") String checkType,
               @Param("expected") String expected, @Param("actual") String actual,
               @Param("verdict") String verdict, @Param("detail") String detail, @Param("now") long now);

    /** 过滤查询：object_id 与 verdict 都可空（空=不过滤），按核验时刻降序。 */
    @Select("""
            <script>
            SELECT id AS "id", object_id AS "objectId", check_type AS "checkType",
                expected AS "expected", actual AS "actual", verdict AS "verdict",
                detail AS "detail", checked_at AS "checkedAt"
            FROM datafrog_retention_check_record
            <where>
                <if test="objectId != null and objectId != ''">object_id = #{objectId}</if>
                <if test="verdict != null and verdict != ''">
                    <if test="objectId != null and objectId != ''">AND</if> verdict = #{verdict}
                </if>
            </where>
            ORDER BY checked_at DESC, id DESC LIMIT #{limit}
            </script>
            """)
    List<RetentionCheckRow> query(@Param("objectId") String objectId, @Param("verdict") String verdict,
                                  @Param("limit") int limit);
}
