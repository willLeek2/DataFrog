package world.willfrog.datafrog.server.task;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 生成任务表的 MyBatis 映射（014-data-02）。注解形态，不引入 XML 映射文件。
 */
@Mapper
public interface GenerationTaskMapper {

    @Insert("INSERT INTO datafrog_generation_task "
            + "(id, user_id, target_object_name, spec_json, client_request_id, status, credit_charged) "
            + "VALUES (#{id}, #{userId}, #{targetObjectName}, #{specJson}, #{clientRequestId}, #{status}, #{creditCharged})")
    int insert(GenerationTaskEntity task);

    @Select("SELECT * FROM datafrog_generation_task WHERE id = #{id}")
    GenerationTaskEntity findById(@Param("id") String id);

    @Select("SELECT * FROM datafrog_generation_task WHERE user_id = #{userId} AND client_request_id = #{clientRequestId}")
    GenerationTaskEntity findByClientRequestId(@Param("userId") String userId, @Param("clientRequestId") String clientRequestId);

    /**
     * 取同一目标对象名的非终态任务（互斥检查用）。部分唯一索引保证至多一条。
     */
    @Select("SELECT * FROM datafrog_generation_task WHERE target_object_name = #{targetObjectName} "
            + "AND status IN ('ACCEPTED', 'RUNNING')")
    GenerationTaskEntity findActiveByTargetObjectName(@Param("targetObjectName") String targetObjectName);

    /**
     * 置终态。WHERE 带非终态守卫：两个并发惰性推进只有一个能写上，更新 0 行的调用方跳过结算，
     * 防止同一任务被重复 settle（与 014-data-01 的 advanceWatermark 同一守卫形态）。
     */
    @Update("UPDATE datafrog_generation_task SET status = #{status}, run_id = #{runId}, "
            + "error_code = #{errorCode}, error_message = #{errorMessage}, "
            + "credit_settled = #{creditSettled}, finished_at = #{finishedAt}, updated_at = now() "
            + "WHERE id = #{id} AND status IN ('ACCEPTED', 'RUNNING')")
    int updateTerminal(GenerationTaskEntity task);

    @Update("UPDATE datafrog_generation_task SET status = #{status}, run_id = #{runId}, updated_at = now() "
            + "WHERE id = #{id}")
    int updateRunning(@Param("id") String id, @Param("status") String status, @Param("runId") String runId);
}
