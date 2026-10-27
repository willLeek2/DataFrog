package world.willfrog.datafrog.server.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时检查 05 的库表增量都在（014-data-05 方案 D12）：对象行三列（retention_days、
 * version_keep_last、deleted_at）、版本行一列（content_purged_at）、两张新表
 * （datafrog_retention_check_record、datafrog_export_rate_ledger）。缺失即启动失败——
 * 纪律与 01/02 一致：DDL 可重复执行、部署前执行、缺了就立即失败而不是等到第一次
 * 清理或限额判断才暴露。DDL 文件 db/datafrog-objectstore-v3.sql。
 */
@Component
public class RetentionSchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionSchemaGuard.class);

    private final JdbcTemplate jdbcTemplate;

    public RetentionSchemaGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 用 COUNT(*) 而非 SELECT 1 LIMIT 1：空表返回 0 行会让 queryForObject 抛
            // EmptyResultDataAccessException，把「表存在但为空」误判成缺表（02 泳道首战即踩中）。
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datafrog_retention_check_record", Integer.class);
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datafrog_export_rate_ledger", Integer.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "retention tables missing or unreadable; "
                            + "execute datafrog-server/src/main/resources/db/datafrog-objectstore-v3.sql before deployment",
                    e);
        }
        try {
            Integer columns = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'datafrog_data_object' "
                            + "AND column_name IN ('retention_days', 'version_keep_last', 'deleted_at')",
                    Integer.class);
            if (columns == null || columns != 3) {
                throw new IllegalStateException("datafrog_data_object 缺保留策略列（预期 3 列，见到 " + columns + "）");
            }
            Integer versionColumn = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'datafrog_object_version' "
                            + "AND column_name = 'content_purged_at'",
                    Integer.class);
            if (versionColumn == null || versionColumn != 1) {
                throw new IllegalStateException("datafrog_object_version 缺 content_purged_at 列");
            }
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "retention columns missing; "
                            + "execute datafrog-server/src/main/resources/db/datafrog-objectstore-v3.sql before deployment",
                    e);
        }
        log.info("schema guard passed: retention tables and columns present");
    }
}
