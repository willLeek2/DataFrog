package world.willfrog.datafrog.server.objectdata.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 数据对象版本行。数据覆盖三件（row_count、coverage_end、content_checksum）的唯一更新入口是
 * {@link #advanceWatermark}，追加 part 后调用：三件一起推进，同时把新 part 的 sha256
 * 追加进 part_checksums、part_count 递增——不存在绕过它的第二写入口。
 */
@Mapper
public interface DataObjectVersionMapper {

    @Insert("""
            INSERT INTO datafrog_object_version (object_id, version, version_note, prev_version,
                coverage_start, content_path, part_count, part_checksums, row_count, coverage_end,
                content_checksum, status, created_at, watermark_updated_at)
            VALUES (#{objectId}, #{version}, #{versionNote}, #{prevVersion}, #{coverageStart},
                #{contentPath}, 0, CAST('[]' AS JSONB), 0, '', '', 'ACTIVE', #{now}, #{now})
            """)
    int insert(@Param("objectId") String objectId, @Param("version") int version,
               @Param("versionNote") String versionNote, @Param("prevVersion") Integer prevVersion,
               @Param("coverageStart") String coverageStart, @Param("contentPath") String contentPath,
               @Param("now") long now);

    /**
     * 版本行插入时不落 part（先有行再有内容落盘）；part 落盘成功后由本方法推进数据覆盖事实。
     * newPartChecksum 传 JSON 编码后的标量（即带双引号的 sha256 串），拼接用 JSONB 的 || 操作符。
     */
    @Update("""
            UPDATE datafrog_object_version SET
                part_count = part_count + 1,
                part_checksums = part_checksums || CAST(#{newPartChecksum} AS JSONB),
                row_count = #{rowCount},
                coverage_end = #{coverageEnd},
                content_checksum = #{contentChecksum},
                watermark_updated_at = #{now}
            WHERE object_id = #{objectId} AND version = #{version} AND status = 'ACTIVE'
            """)
    int advanceWatermark(@Param("objectId") String objectId, @Param("version") int version,
                         @Param("newPartChecksum") String newPartChecksum,
                         @Param("rowCount") long rowCount, @Param("coverageEnd") String coverageEnd,
                         @Param("contentChecksum") String contentChecksum, @Param("now") long now);

    @Select("""
            SELECT object_id AS "objectId", version AS "version", version_note AS "versionNote",
                prev_version AS "prevVersion", coverage_start AS "coverageStart",
                content_path AS "contentPath", part_count AS "partCount",
                part_checksums::text AS "partChecksumsJson", row_count AS "rowCount",
                coverage_end AS "coverageEnd", content_checksum AS "contentChecksum",
                continuous_complete_date AS "continuousCompleteDate",
                has_gap_after_watermark AS "hasGapAfterWatermark",
                status AS "status", created_at AS "createdAt", watermark_updated_at AS "watermarkUpdatedAt"
            FROM datafrog_object_version WHERE object_id = #{objectId} AND version = #{version}
            """)
    DataObjectVersionRow get(@Param("objectId") String objectId, @Param("version") int version);

    @Select("""
            SELECT object_id AS "objectId", version AS "version", version_note AS "versionNote",
                prev_version AS "prevVersion", coverage_start AS "coverageStart",
                content_path AS "contentPath", part_count AS "partCount",
                part_checksums::text AS "partChecksumsJson", row_count AS "rowCount",
                coverage_end AS "coverageEnd", content_checksum AS "contentChecksum",
                continuous_complete_date AS "continuousCompleteDate",
                has_gap_after_watermark AS "hasGapAfterWatermark",
                status AS "status", created_at AS "createdAt", watermark_updated_at AS "watermarkUpdatedAt"
            FROM datafrog_object_version
            WHERE object_id = #{objectId} AND status = 'ACTIVE'
            """)
    DataObjectVersionRow getActive(@Param("objectId") String objectId);

    /** 升版封存：旧版本行置 SUPERSEDED；封存后 advanceWatermark 的 WHERE 条件使其不再可写。 */
    @Update("UPDATE datafrog_object_version SET status = 'SUPERSEDED' WHERE object_id = #{objectId} AND version = #{version}")
    int supersede(@Param("objectId") String objectId, @Param("version") int version);

    @Select("SELECT to_regclass('datafrog_object_version') IS NOT NULL")
    boolean tableExists();

    @Select("SELECT to_regclass('datafrog_catalog_record') IS NOT NULL")
    boolean catalogTableExists();
}
