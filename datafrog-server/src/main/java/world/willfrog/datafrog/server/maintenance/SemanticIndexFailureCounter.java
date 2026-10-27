package world.willfrog.datafrog.server.maintenance;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语义索引写入与删除的失败计数（014-data-05 方案 D9，06 审查 P2-3 遗留：当时结论
 * 「仓库无度量设施，05 期随重建入口一并定」）。进程内两个 AtomicLong，计数点在
 * DataObjectRegistryService 的 upsertIndexQuietly 捕获处与删除路径的 deleteIndexQuietly
 * 捕获处各一行；查询面经维护端点 GET /maintenance/semantic-index/status 透出。
 *
 * 如实写明形态：这是进程内计数不是持久指标——进程重启清零；要长期趋势时看出处在日志
 * （两处捕获都记 error 日志，计数只是聚合视图）。
 */
@Component
public class SemanticIndexFailureCounter {

    private final AtomicLong writeFailures = new AtomicLong();
    private final AtomicLong deleteFailures = new AtomicLong();

    public void onWriteFailure() {
        writeFailures.incrementAndGet();
    }

    public void onDeleteFailure() {
        deleteFailures.incrementAndGet();
    }

    /** status 端点的应答载荷：两计数的即时快照。 */
    public Map<String, Long> snapshot() {
        return Map.of(
                "writeFailures", writeFailures.get(),
                "deleteFailures", deleteFailures.get());
    }
}
