package world.willfrog.datafrog.server.maintenance;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 导出触发限额的计数流水（014-data-05 方案 D8）。表 datafrog_export_rate_ledger
 * （DDL 见 db/datafrog-objectstore-v3.sql）。两个判断都查本表：每日次数按
 * user_id + exported_at 窗口计数；同对象冷却按 user_id + object_id 取最近一条。
 */
@Mapper
public interface ExportRateLedgerMapper {

    @Insert("INSERT INTO datafrog_export_rate_ledger (user_id, object_id, exported_at) "
            + "VALUES (#{userId}, #{objectId}, #{now})")
    int insert(@Param("userId") String userId, @Param("objectId") String objectId, @Param("now") long now);

    /** 某用户自 dayStartMs（含）以来的导出触发次数（每日额度判断）。 */
    @Select("SELECT COUNT(*) FROM datafrog_export_rate_ledger "
            + "WHERE user_id = #{userId} AND exported_at >= #{dayStartMs}")
    long countSince(@Param("userId") String userId, @Param("dayStartMs") long dayStartMs);

    /** 某用户对某对象最近一次导出触发的时刻；从未导过返回 null（冷却判断）。 */
    @Select("SELECT MAX(exported_at) FROM datafrog_export_rate_ledger "
            + "WHERE user_id = #{userId} AND object_id = #{objectId}")
    Long lastExportedAt(@Param("userId") String userId, @Param("objectId") String objectId);
}
