package world.willfrog.datafrog.server.maintenance;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.WithPayloadSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import world.willfrog.datafrog.server.httpapi.ApiException;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectRow;
import world.willfrog.datafrog.server.semanticindex.QdrantClientProvider;
import world.willfrog.datafrog.server.semanticindex.SemanticIndexService;
import world.willfrog.datafrog.server.semanticindex.SemanticIndexSettings;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语义索引的重建入口（014-data-05 方案 D9，06 遗留件）。两段执行，全部在
 * semanticindex 包外侧组装，不改 SemanticIndexService 的三件接口（06 方案 D6 的约束）：
 *
 * 第一段全量重写：扫描全部 ACTIVE 对象逐个调用 upsert——登记卡四要素对象行全有，
 * 幂等覆盖同一点；
 * 第二段孤儿对账：经 QdrantClientProvider 用 scroll 全量列举集合里的点，点的
 * object_id 对应对象不是 ACTIVE 的逐个 deleteByObjectId——覆盖删除时同步摘除上线前的
 * 历史残留与摘除失败的漏网（台账 S8 的对账补充）。
 *
 * 并发防护用进程内原子标志：重复触发应答 409 REBUILD_IN_PROGRESS；不加分布式锁
 * （单实例部署，方案如实写明）。
 */
@Component
public class SemanticIndexRebuildService {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndexRebuildService.class);

    /** scroll 每页取点数；全量列举靠 nextPageOffset 翻页直到取空。 */
    private static final int SCROLL_PAGE_SIZE = 256;

    private final DataObjectMapper objectMapper;
    private final SemanticIndexService semanticIndex;
    private final QdrantClientProvider qdrantClientProvider;
    private final SemanticIndexSettings semanticIndexSettings;
    private final SemanticIndexFailureCounter failureCounter;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public SemanticIndexRebuildService(DataObjectMapper objectMapper,
                                       SemanticIndexService semanticIndex,
                                       QdrantClientProvider qdrantClientProvider,
                                       SemanticIndexSettings semanticIndexSettings,
                                       SemanticIndexFailureCounter failureCounter) {
        this.objectMapper = objectMapper;
        this.semanticIndex = semanticIndex;
        this.qdrantClientProvider = qdrantClientProvider;
        this.semanticIndexSettings = semanticIndexSettings;
        this.failureCounter = failureCounter;
    }

    /** 重建应答的四个计数（方案 D9）。 */
    public record RebuildResult(int scanned, int upserted, int orphansRemoved, int failures) {
    }

    /**
     * 执行一次全量重建加孤儿对账。任一单点失败不中断整体：记入 failures 计数继续
     * 下一个（重建的目的就是把索引拉回与库一致，个别失败暴露在应答计数里由人工重触发）。
     */
    public RebuildResult rebuild() {
        if (!running.compareAndSet(false, true)) {
            throw new ApiException(HttpStatus.CONFLICT, "REBUILD_IN_PROGRESS", "索引重建正在进行中，稍后再试");
        }
        try {
            int scanned = 0;
            int upserted = 0;
            int failures = 0;
            // 第一段：全量重写
            int offset = 0;
            while (true) {
                List<DataObjectRow> page = objectMapper.listActive(200, offset);
                for (DataObjectRow row : page) {
                    scanned++;
                    try {
                        semanticIndex.upsert(row.id(), row.name(), row.description(), row.owner(), row.visibility());
                        upserted++;
                    } catch (Exception e) {
                        failures++;
                        failureCounter.onWriteFailure();
                        log.error("rebuild upsert failed: object={} err={}", row.id(), e.toString());
                    }
                }
                if (page.size() < 200) {
                    break;
                }
                offset += 200;
            }
            // 第二段：孤儿对账
            int orphansRemoved = 0;
            try {
                orphansRemoved = removeOrphanPoints();
            } catch (Exception e) {
                failures++;
                log.error("rebuild orphan scan failed: {}", e.toString());
            }
            log.info("semantic index rebuild done: scanned={} upserted={} orphansRemoved={} failures={}",
                    scanned, upserted, orphansRemoved, failures);
            return new RebuildResult(scanned, upserted, orphansRemoved, failures);
        } finally {
            running.set(false);
        }
    }

    /**
     * 孤儿对账：scroll 全量列举集合里的点，payload 的 object_id 对应对象不是 ACTIVE 的
     * 逐个摘除。命中点缺 object_id 载荷的按孤儿处理（载荷残缺的点无法回表对账，摘除后
     * 下次登记/重建会按库重建正确的点）。
     */
    private int removeOrphanPoints() throws Exception {
        QdrantClient client = qdrantClientProvider.get();
        String collection = semanticIndexSettings.getCollection();
        int removed = 0;
        io.qdrant.client.grpc.Common.PointId pageOffset = null;
        while (true) {
            ScrollPoints.Builder builder = ScrollPoints.newBuilder()
                    .setCollectionName(collection)
                    .setLimit(SCROLL_PAGE_SIZE)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());
            if (pageOffset != null) {
                builder.setOffset(pageOffset);
            }
            ScrollResponse response = client.scrollAsync(builder.build()).get(60, TimeUnit.SECONDS);
            for (RetrievedPoint point : response.getResultList()) {
                String objectId = point.getPayloadMap().containsKey("object_id")
                        ? point.getPayloadMap().get("object_id").getStringValue()
                        : null;
                boolean orphan = objectId == null || objectId.isBlank();
                if (!orphan) {
                    DataObjectRow row = objectMapper.getById(objectId);
                    orphan = row == null || !"ACTIVE".equals(row.status());
                }
                if (orphan) {
                    try {
                        if (objectId != null && !objectId.isBlank()) {
                            semanticIndex.deleteByObjectId(objectId);
                        } else {
                            // 载荷缺 object_id 的点没法经 deleteByObjectId 定位（它按对象 id
                            // 推导点 id），只能按点自身的 id 直删；两条路删的是同一个点。
                            client.deleteAsync(collection, List.of(point.getId())).get(30, TimeUnit.SECONDS);
                        }
                        removed++;
                    } catch (Exception e) {
                        failureCounter.onDeleteFailure();
                        log.error("rebuild orphan delete failed: objectId={} pointId={} err={}",
                                objectId, point.getId(), e.toString());
                    }
                }
            }
            if (!response.hasNextPageOffset()) {
                break;
            }
            pageOffset = response.getNextPageOffset();
        }
        return removed;
    }
}
