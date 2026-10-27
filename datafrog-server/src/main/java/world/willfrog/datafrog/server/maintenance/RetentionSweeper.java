package world.willfrog.datafrog.server.maintenance;

import io.qdrant.client.QdrantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.datafrog.server.config.DatafrogRuntimeConfig;
import world.willfrog.datafrog.server.config.DatafrogRuntimeConfigLoader;
import world.willfrog.datafrog.server.objectdata.domain.CatalogAction;
import world.willfrog.datafrog.server.objectdata.repo.CatalogRecordMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectRow;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectVersionMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectVersionRow;
import world.willfrog.datafrog.server.objectdata.service.ContentStorageService;
import world.willfrog.datafrog.server.semanticindex.QdrantClientProvider;
import world.willfrog.datafrog.server.semanticindex.SemanticIndexService;
import world.willfrog.datafrog.server.semanticindex.SemanticIndexSettings;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.qdrant.client.PointIdFactory.id;

/**
 * 到期清理与留存核验的执行体（014-data-05 方案 D6/D7）。平台第一个后台定时执行体：
 * 02 的状态推进特意做成惰性判定（有查询可以寄生），清理动作没有用户请求可寄生，必须
 * 主动执行，性质不同。间隔经热配置 datafrog.maintenance.sweep-interval-ms（默认六小时）。
 *
 * <p>启用前的行为约束（bob 裁定补句）：frog 对保留策略点头之前，本执行体不删除任何
 * 内容文件——每个周期只做全量存在性核验（RETAIN 记录）；物理删除从点头那一刻起才
 * 可能发生。启用开关 {@link #POLICY_ENABLED} 与登记侧「带非空保留值直接拒绝」的第二道闸
 * （DataObjectRegistryService.register）是同一个拍板的两处落点，点头后一起改。</p>
 *
 * <p>单实例部署，调度不加分布式锁（bob 裁定）；如实写明：本轮只有一个实例，如果以后
 * 起了第二个实例，两份定时任务会重复删除文件（删除本身幂等，浪费与竞态是真的）。</p>
 *
 * <p>周期间隔的实现：@Scheduled 节拍固定 60 秒，到期判定用热配置间隔（默认六小时）
 * 与上次执行时刻比较——这样改热配置间隔不用重启进程；节拍取 60 秒是核验粒度与空转
 * 成本的折中，不是清理间隔本身。</p>
 */
@Component
public class RetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweeper.class);

    /** 物理删除总闸：frog 对保留策略点头前保持 false（见类注释）。 */
    static final boolean POLICY_ENABLED = false;

    /** @Scheduled 节拍（毫秒）；真正的周期间隔看热配置 maintenance-sweep-interval-ms。 */
    private static final long TICK_MS = 60_000;
    /** 默认周期间隔：六小时（bob 裁定）。 */
    private static final long DEFAULT_SWEEP_INTERVAL_MS = 6L * 3600 * 1000;
    private static final int PAGE_SIZE = 200;
    private static final long MILLIS_PER_DAY = 24L * 3600 * 1000;

    private final DataObjectMapper objectMapper;
    private final DataObjectVersionMapper versionMapper;
    private final CatalogRecordMapper catalogMapper;
    private final ContentStorageService contentStore;
    private final SemanticIndexService semanticIndex;
    private final QdrantClientProvider qdrantClientProvider;
    private final SemanticIndexSettings semanticIndexSettings;
    private final RetentionCheckRecorder checkRecorder;
    private final DatafrogRuntimeConfigLoader configLoader;

    private volatile long lastSweepAt = 0;

    public RetentionSweeper(DataObjectMapper objectMapper,
                            DataObjectVersionMapper versionMapper,
                            CatalogRecordMapper catalogMapper,
                            ContentStorageService contentStore,
                            SemanticIndexService semanticIndex,
                            QdrantClientProvider qdrantClientProvider,
                            SemanticIndexSettings semanticIndexSettings,
                            RetentionCheckRecorder checkRecorder,
                            DatafrogRuntimeConfigLoader configLoader) {
        this.objectMapper = objectMapper;
        this.versionMapper = versionMapper;
        this.catalogMapper = catalogMapper;
        this.contentStore = contentStore;
        this.semanticIndex = semanticIndex;
        this.qdrantClientProvider = qdrantClientProvider;
        this.semanticIndexSettings = semanticIndexSettings;
        this.checkRecorder = checkRecorder;
        this.configLoader = configLoader;
    }

    @Scheduled(fixedDelay = TICK_MS)
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastSweepAt < sweepIntervalMs()) {
            return;
        }
        lastSweepAt = now;
        try {
            sweep();
        } catch (Exception e) {
            // 周期失败不冒泡（冒泡只会让调度线程死掉）；核验记录与日志负责暴露
            log.error("retention sweep failed, will retry next interval: {}", e.toString(), e);
        }
    }

    /** 一个清理周期：全量存在性核验 + （启用后）到期物理清理与版本级保留。 */
    public void sweep() {
        verifyActiveObjects();
        if (POLICY_ENABLED) {
            purgeDeletedObjects();
            trimOldVersions();
        } else {
            log.debug("retention policy not enabled, physical purge skipped");
        }
    }

    /**
     * 每个周期对未到期的 ACTIVE 对象全量核验一遍内容文件在不在（只看存在、不读内容，
     * 不抽样——平台初期对象不多，抽样省不了多少事、还留漏检口子）。缺文件的记 VIOLATION。
     */
    private void verifyActiveObjects() {
        int offset = 0;
        int checked = 0;
        int violations = 0;
        while (true) {
            List<DataObjectRow> page = objectMapper.listActive(PAGE_SIZE, offset);
            for (DataObjectRow row : page) {
                DataObjectVersionRow active = versionMapper.getActive(row.id());
                String contentPath = active == null ? row.id() + "/v" + row.currentVersion() : active.contentPath();
                boolean present = contentStore.hasContentFiles(contentPath);
                checkRecorder.recordRetain(row.id(), present,
                        present ? null : "版本目录没有内容 part: " + contentPath);
                checked++;
                if (!present) {
                    violations++;
                }
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
        log.info("retention retain-check done: checked={} violations={}", checked, violations);
    }

    /**
     * 到期物理清理（对象级，retention_days）：删除该对象在数据根下的整个目录（内容文件
     * 与历次导出物）、幂等再摘除一次向量点（防删除时同步摘除失败后的残留）、全部版本行
     * 标记 content_purged_at（行本身保留）、台账写 PURGED（actor=retention-sweeper）。
     * 对象行与台账记录永不物理删除——它们是出处规则的档案面（方案 D5）。
     * 删除后逐条回读核验（文件确实不存在、向量点确实不存在）写 PURGE 记录。
     */
    private void purgeDeletedObjects() {
        long now = System.currentTimeMillis();
        int offset = 0;
        while (true) {
            List<DataObjectRow> page = objectMapper.listDeleted(PAGE_SIZE, offset);
            for (DataObjectRow row : page) {
                if (row.retentionDays() == null || row.deletedAt() == null
                        || now < row.deletedAt() + row.retentionDays() * MILLIS_PER_DAY) {
                    continue;
                }
                purgeObject(row, now);
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
    }

    /** 版本级保留（version_keep_last，ACTIVE 对象）：只删超保留个数的旧版本内容文件并
     * 标记 content_purged_at，版本行保留；当前 ACTIVE 版本永不删。 */
    private void trimOldVersions() {
        int offset = 0;
        while (true) {
            List<DataObjectRow> page = objectMapper.listActive(PAGE_SIZE, offset);
            for (DataObjectRow row : page) {
                if (row.versionKeepLast() == null) {
                    continue;
                }
                List<DataObjectVersionRow> versions = versionMapper.listByObject(row.id());
                int kept = 0;
                for (DataObjectVersionRow v : versions) { // 按 version 降序
                    if ("ACTIVE".equals(v.status())) {
                        kept++;
                        continue;
                    }
                    if (v.contentPurgedAt() != null) {
                        continue;
                    }
                    if (kept < row.versionKeepLast()) {
                        kept++;
                        continue;
                    }
                    contentStore.deleteVersionDirQuietly(v.contentPath());
                    versionMapper.markContentPurged(row.id(), v.version(), System.currentTimeMillis());
                }
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
            offset += PAGE_SIZE;
        }
    }

    private void purgeObject(DataObjectRow row, long now) {
        try {
            contentStore.deleteObjectTreeQuietly(row.id());
        } catch (Exception e) {
            log.error("delete object tree failed (recorded as VIOLATION by check): object={} err={}",
                    row.id(), e.toString());
        }
        // 幂等再摘一次向量点：防删除时同步摘除（D5）失败后的残留
        try {
            semanticIndex.deleteByObjectId(row.id());
        } catch (Exception e) {
            log.error("re-delete vector point failed: object={} err={}", row.id(), e.toString());
        }
        for (DataObjectVersionRow v : versionMapper.listByObject(row.id())) {
            if (v.contentPurgedAt() == null) {
                versionMapper.markContentPurged(row.id(), v.version(), now);
            }
        }
        catalogMapper.insert(row.id(), CatalogAction.PURGED.name(), "retention-sweeper",
                "软删满 " + row.retentionDays() + " 天物理清理（内容与导出物删除，行保留）", now);
        // 回读核验：文件确实不存在、向量点确实不存在
        boolean fileAbsent = !contentStore.hasContentFiles(row.id() + "/v" + row.currentVersion());
        boolean pointAbsent = false;
        try {
            pointAbsent = !pointExists(row.id());
        } catch (Exception e) {
            checkRecorder.recordPurge(row.id(), fileAbsent, false,
                    "向量点存在性核验失败（按未删处理）: " + e.getMessage());
            return;
        }
        checkRecorder.recordPurge(row.id(), fileAbsent, pointAbsent, null);
    }

    /**
     * 向量点存在性核验（PURGE 记录用）：点 id 的派生与 QdrantSemanticIndexService 同一
     * 口径（UUID.nameUUIDFromBytes(对象 id)）。semanticindex 包的接口三件不含读取，这里
     * 经 QdrantClientProvider 只读查询，不改那个包（方案 D9 同一约束）。
     */
    private boolean pointExists(String objectId) throws Exception {
        QdrantClient client = qdrantClientProvider.get();
        UUID pointUuid = UUID.nameUUIDFromBytes(objectId.getBytes(StandardCharsets.UTF_8));
        // ReadConsistency 传 null=用集合默认一致性级别（客户端对该参数按可空处理）
        return !client.retrieveAsync(semanticIndexSettings.getCollection(), List.of(id(pointUuid)), false, false, null)
                .get(5, TimeUnit.SECONDS)
                .isEmpty();
    }

    private long sweepIntervalMs() {
        DatafrogRuntimeConfig cfg = configLoader.current().orElse(null);
        Long hot = cfg == null ? null : cfg.getMaintenanceSweepIntervalMs();
        return hot != null && hot > 0 ? hot : DEFAULT_SWEEP_INTERVAL_MS;
    }
}
