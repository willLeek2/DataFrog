package world.willfrog.datafrog.server.task;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 修改任务表的 MyBatis 映射（014-data-05）。注解形态，不引入 XML 映射文件。
 * 方法形态镜像 GenerationTaskMapper；登记段专用的两个更新（记登记版本、登记失败置
 * FAILED_INCOMPLETE）是 05 的新增，守卫条件见各方法注释。
 */
@Mapper
public interface ModificationTaskMapper {

    @Insert("INSERT INTO datafrog_modification_task "
            + "(id, user_id, base_object_id, base_version, version_note, spec_json, client_request_id, "
            + "status, credit_charged) "
            + "VALUES (#{id}, #{userId}, #{baseObjectId}, #{baseVersion}, #{versionNote}, #{specJson}, "
            + "#{clientRequestId}, #{status}, #{creditCharged})")
    int insert(ModificationTaskEntity task);

    @Select("SELECT * FROM datafrog_modification_task WHERE id = #{id}")
    ModificationTaskEntity findById(@Param("id") String id);

    @Select("SELECT * FROM datafrog_modification_task WHERE user_id = #{userId} AND client_request_id = #{clientRequestId}")
    ModificationTaskEntity findByClientRequestId(@Param("userId") String userId, @Param("clientRequestId") String clientRequestId);

    /**
     * 取同一基准对象的非终态任务（互斥检查用）。部分唯一索引保证至多一条。
     */
    @Select("SELECT * FROM datafrog_modification_task WHERE base_object_id = #{baseObjectId} "
            + "AND status IN ('ACCEPTED', 'RUNNING')")
    ModificationTaskEntity findActiveByBaseObjectId(@Param("baseObjectId") String baseObjectId);

    /**
     * 置终态。WHERE 带非终态守卫：两个并发惰性推进只有一个能写上，更新 0 行的调用方跳过结算，
     * 防止同一任务被重复 settle（与 GenerationTaskMapper 同一守卫形态）。
     */
    @Update("UPDATE datafrog_modification_task SET status = #{status}, run_id = #{runId}, "
            + "error_code = #{errorCode}, error_message = #{errorMessage}, "
            + "credit_settled = #{creditSettled}, finished_at = #{finishedAt}, updated_at = now() "
            + "WHERE id = #{id} AND status IN ('ACCEPTED', 'RUNNING')")
    int updateTerminal(ModificationTaskEntity task);

    @Update("UPDATE datafrog_modification_task SET status = #{status}, run_id = #{runId}, updated_at = now() "
            + "WHERE id = #{id}")
    int updateRunning(@Param("id") String id, @Param("status") String status, @Param("runId") String runId);

    /**
     * 登记段成功：记录升到的版本号。WHERE 带 SUCCEEDED 守卫与 registered_version IS NULL，
     * 并发重入登记段只有一个能写上；写不上说明另一路已登记，静默让位。
     */
    @Update("UPDATE datafrog_modification_task SET registered_version = #{registeredVersion}, "
            + "columns_confirmed = FALSE, updated_at = now() "
            + "WHERE id = #{id} AND status = 'SUCCEEDED' AND registered_version IS NULL")
    int updateRegisteredVersion(@Param("id") String id, @Param("registeredVersion") Integer registeredVersion);

    /**
     * 登记段失败：SUCCEEDED 改判 FAILED_INCOMPLETE（02 产物入库失败的同型口径，05 由登记段写）。
     * WHERE 守卫保证只改判一次；已是 FAILED_INCOMPLETE 的重复触发让位（终态不回改、如实留痕）。
     * 结算不动：运行真实消耗的积分已在置 SUCCEEDED 时实结，登记失败不是运行的过错。
     */
    @Update("UPDATE datafrog_modification_task SET status = 'FAILED_INCOMPLETE', incomplete = TRUE, "
            + "error_code = #{errorCode}, error_message = #{errorMessage}, updated_at = now() "
            + "WHERE id = #{id} AND status = 'SUCCEEDED'")
    int updateRegistrationFailed(@Param("id") String id, @Param("errorCode") String errorCode,
                                 @Param("errorMessage") String errorMessage);
}
