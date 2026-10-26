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

    @Select("SELECT * FROM datafrog_generation_task WHERE client_request_id = #{clientRequestId}")
    GenerationTaskEntity findByClientRequestId(@Param("clientRequestId") String clientRequestId);

    /**
     * 取同一目标对象名的非终态任务（互斥检查用）。部分唯一索引保证至多一条。
     */
    @Select("SELECT * FROM datafrog_generation_task WHERE target_object_name = #{targetObjectName} "
            + "AND status IN ('ACCEPTED', 'RUNNING')")
    GenerationTaskEntity findActiveByTargetObjectName(@Param("targetObjectName") String targetObjectName);

    @Update("UPDATE datafrog_generation_task SET status = #{status}, run_id = #{runId}, "
            + "error_code = #{errorCode}, error_message = #{errorMessage}, "
            + "credit_settled = #{creditSettled}, finished_at = #{finishedAt}, updated_at = now() "
            + "WHERE id = #{id}")
    int updateTerminal(GenerationTaskEntity task);

    @Update("UPDATE datafrog_generation_task SET status = #{status}, run_id = #{runId}, updated_at = now() "
            + "WHERE id = #{id}")
    int updateRunning(@Param("id") String id, @Param("status") String status, @Param("runId") String runId);
}
