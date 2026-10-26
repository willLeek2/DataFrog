package world.willfrog.datafrog.server.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时检查生成任务表存在，缺失即启动失败（014-data-02，与 014-data-01 的 D10 纪律一致：
 * DDL 可重复执行、不用迁移框架、泳道部署进程启动前执行；表不存在说明部署前置步骤漏了，
 * 应当立即失败而不是等到第一次受理才暴露）。
 */
@Component
public class GenerationTaskSchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GenerationTaskSchemaGuard.class);

    private final JdbcTemplate jdbcTemplate;

    public GenerationTaskSchemaGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.queryForObject("SELECT 1 FROM datafrog_generation_task LIMIT 1", Integer.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "table datafrog_generation_task missing or unreadable; execute db/datafrog_generation_task.sql before deployment",
                    e);
        }
        log.info("schema guard passed: datafrog_generation_task present");
    }
}
