package world.willfrog.datafrog.server.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时检查修改任务表存在，缺失即启动失败（014-data-05，与 01/02 的 D10 纪律一致：
 * DDL 可重复执行、不用迁移框架、泳道部署进程启动前执行；表不存在说明部署前置步骤漏了，
 * 应当立即失败而不是等到第一次受理才暴露）。
 */
@Component
public class ModificationTaskSchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ModificationTaskSchemaGuard.class);

    private final JdbcTemplate jdbcTemplate;

    public ModificationTaskSchemaGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 用 COUNT(*) 而非 SELECT 1 LIMIT 1：空表返回 0 行会让 queryForObject 抛
            // EmptyResultDataAccessException，把「表存在但为空」误判成缺表（02 泳道首战即踩中）。
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM datafrog_modification_task", Integer.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "table datafrog_modification_task missing or unreadable; "
                            + "execute datafrog-server/src/main/resources/db/datafrog-modification-task-v1.sql before deployment",
                    e);
        }
        log.info("schema guard passed: datafrog_modification_task present");
    }
}
