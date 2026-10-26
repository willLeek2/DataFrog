package world.willfrog.datafrog.server.objectdata.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.datafrog.server.objectdata.domain.CatalogAction;
import world.willfrog.datafrog.server.objectdata.domain.DataObject;
import world.willfrog.datafrog.server.objectdata.domain.SemanticRef;
import world.willfrog.datafrog.server.objectdata.repo.CatalogRecordMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectRow;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectVersionMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectVersionRow;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据对象登记门面：014-data-02/03 的调用入口。
 *
 * 写入次序（登记/升版）：挂靠校验→约定校验→内容先落盘（Parquet part）→元数据一个事务内落库
 * （对象行/版本行/台账）→元数据失败时尽力删掉已落盘文件。内容先行的原因：反过来会出现
 * 「有登记卡无数据」的假对象；孤儿文件可被清理，假对象会污染台账。
 *
 * 台账 actor：本模块是 Dubbo 内部入口，还没有调用方身份（身份与鉴权归 014-data-06 对外接口层），
 * v1 以 owner 字段（登记）或 system（追加/升版/删除）记台账，06 接入后替换为真实调用方。
 */
@Service
public class DataObjectRegistryService {

    /** 登记命令（rpc 层从 proto 请求装配）。 */
    public record RegisterCommand(
            String name,
            String description,
            String sourceType,
            String owner,
            String visibility,
            List<SemanticRef> semanticRefs,
            String coverageScope,
            String frequency,
            String genMethod,
            String genTask,
            List<String> inputs,
            List<String> primaryKey,
            String adjustment,
            String ccy,
            String exportFormat,
            String compression,
            String coverageStart,
            String coverageEnd,
            byte[] csvContent) {
    }

    /** 登记卡视图：对象行 + ACTIVE 版本行水位四项的读取时投影。 */
    public record CardView(DataObject object, long rowCount, String coverageStart, String coverageEnd,
                           String contentChecksum) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataObjectMapper objectMapper;
    private final DataObjectVersionMapper versionMapper;
    private final CatalogRecordMapper catalogMapper;
    private final SemanticDefinitionService definitionService;
    private final ContentStorageService contentStore;
    private final ConventionValidator validator;
    private final TransactionTemplate tx;

    public DataObjectRegistryService(DataObjectMapper objectMapper, DataObjectVersionMapper versionMapper,
                                     CatalogRecordMapper catalogMapper, SemanticDefinitionService definitionService,
                                     ContentStorageService contentStore, ConventionValidator validator,
                                     PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.versionMapper = versionMapper;
        this.catalogMapper = catalogMapper;
        this.definitionService = definitionService;
        this.contentStore = contentStore;
        this.validator = validator;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public CardView register(RegisterCommand cmd) {
        definitionService.requireAll(cmd.semanticRefs());
        List<String> missingInputs = new ArrayList<>();
        for (String input : cmd.inputs()) {
            DataObjectRow row = objectMapper.getById(input);
            if (row == null || !"ACTIVE".equals(row.status())) {
                missingInputs.add(input);
            }
        }
        validator.validateRegistration(cmd, missingInputs);

        String id = generateId();
        String contentPath = id + "/v1";
        ContentStorageService.StoredPart part = contentStore.writePart(contentPath, cmd.csvContent());
        String aggregate = contentStore.contentChecksum(contentPath);
        long now = System.currentTimeMillis();
        try {
            tx.executeWithoutResult(status -> {
                objectMapper.insert(toRow(cmd, id, now));
                versionMapper.insert(id, 1, "", null, cmd.coverageStart(), contentPath, now);
                versionMapper.advanceWatermark(id, 1, Jsons.jsonScalar(part.sha256()), part.rowCount(),
                        cmd.coverageEnd(), aggregate, now);
                catalogMapper.insert(id, CatalogAction.REGISTERED.name(), cmd.owner(),
                        "登记 v1，1 个 part，" + part.rowCount() + " 行", now);
            });
        } catch (RuntimeException e) {
            contentStore.deleteVersionDirQuietly(contentPath);
            throw e;
        }
        return get(id);
    }

    public CardView append(String objectId, byte[] csvContent, String note) {
        DataObjectRow object = requireVisible(objectId);
        DataObjectVersionRow active = requireActiveVersion(objectId);
        contentStore.assertSameColumns(active.contentPath(), csvContent);
        ContentStorageService.StoredPart part = contentStore.writePart(active.contentPath(), csvContent);
        String aggregate = contentStore.contentChecksum(active.contentPath());
        String newCoverageEnd = maxCoverageEnd(active.coverageEnd(),
                contentStore.maxDateInPart(active.contentPath(), part.fileName()));
        long now = System.currentTimeMillis();
        try {
            tx.executeWithoutResult(status -> {
                int updated = versionMapper.advanceWatermark(objectId, active.version(),
                        Jsons.jsonScalar(part.sha256()), active.rowCount() + part.rowCount(),
                        newCoverageEnd, aggregate, now);
                if (updated != 1) {
                    // 版本行已非 ACTIVE（并发升版封存），本次追加作废
                    throw new ObjectStoreExceptions.NoActiveVersionException(objectId);
                }
                catalogMapper.insert(objectId, CatalogAction.APPENDED.name(), "system",
                        "追加 " + part.rowCount() + " 行" + (note == null || note.isBlank() ? "" : "：" + note), now);
            });
        } catch (RuntimeException e) {
            contentStore.deletePartQuietly(active.contentPath(), part.fileName());
            throw e;
        }
        return get(objectId);
    }

    public CardView newVersion(String objectId, String versionNote, String coverageStart, String coverageEnd,
                               byte[] csvContent) {
        if (versionNote == null || versionNote.isBlank()) {
            throw new ObjectStoreExceptions.InvalidArgumentException("升版必须给版本说明（修正或重算，规范篇第 9 节）");
        }
        validator.validateCoverage(coverageStart, coverageEnd);
        DataObjectRow object = requireVisible(objectId);
        DataObjectVersionRow active = requireActiveVersion(objectId);
        int newVersion = Math.max(object.currentVersion(), active.version()) + 1;
        if (versionMapper.get(objectId, newVersion) != null) {
            throw new ObjectStoreExceptions.DuplicateVersionException(objectId, newVersion);
        }
        String contentPath = objectId + "/v" + newVersion;
        ContentStorageService.StoredPart part = contentStore.writePart(contentPath, csvContent);
        String aggregate = contentStore.contentChecksum(contentPath);
        long now = System.currentTimeMillis();
        try {
            tx.executeWithoutResult(status -> {
                versionMapper.supersede(objectId, active.version());
                versionMapper.insert(objectId, newVersion, versionNote, active.version(), coverageStart,
                        contentPath, now);
                versionMapper.advanceWatermark(objectId, newVersion, Jsons.jsonScalar(part.sha256()),
                        part.rowCount(), coverageEnd, aggregate, now);
                objectMapper.updateCurrentVersion(objectId, newVersion, now);
                catalogMapper.insert(objectId, CatalogAction.MODIFIED.name(), "system",
                        "升版 v" + active.version() + "→v" + newVersion + "：" + versionNote, now);
            });
        } catch (RuntimeException e) {
            contentStore.deleteVersionDirQuietly(contentPath);
            throw e;
        }
        return get(objectId);
    }

    public void delete(String objectId) {
        requireVisible(objectId);
        long now = System.currentTimeMillis();
        tx.executeWithoutResult(status -> {
            objectMapper.markDeleted(objectId, now);
            catalogMapper.insert(objectId, CatalogAction.DELETED.name(), "system", "软删（保留策略任务前不物理删）", now);
        });
    }

    public CardView get(String objectId) {
        DataObjectRow object = requireVisible(objectId);
        DataObjectVersionRow active = requireActiveVersion(objectId);
        return new CardView(toDomain(object), active.rowCount(), active.coverageStart(),
                active.coverageEnd(), active.contentChecksum());
    }

    public record ListPage(List<CardView> cards, long total) {
    }

    public ListPage list(int limit, int offset) {
        List<CardView> cards = new ArrayList<>();
        for (DataObjectRow row : objectMapper.listActive(limit, offset)) {
            DataObjectVersionRow active = versionMapper.getActive(row.id());
            if (active == null) {
                throw new ObjectStoreExceptions.NoActiveVersionException(row.id());
            }
            cards.add(new CardView(toDomain(row), active.rowCount(), active.coverageStart(),
                    active.coverageEnd(), active.contentChecksum()));
        }
        return new ListPage(cards, objectMapper.countActive());
    }

    public record ExportView(int version, ContentStorageService.ExportResult result) {
    }

    public ExportView export(String objectId, int versionParam) {
        DataObjectRow object = requireVisible(objectId);
        DataObjectVersionRow row = versionParam <= 0
                ? requireActiveVersion(objectId)
                : requireVersion(objectId, versionParam);
        return new ExportView(row.version(), contentStore.exportCsv(objectId, row.version(), row.contentPath(),
                object.exportFormat(), object.compression()));
    }

    public List<String> listCatalogActions(String objectId, int limit) {
        List<String> actions = new ArrayList<>();
        for (var record : catalogMapper.listByObject(objectId, limit)) {
            actions.add(record.action() + " " + record.summary() + " @" + record.createdAt());
        }
        return actions;
    }

    private DataObjectRow requireVisible(String objectId) {
        DataObjectRow row = objectMapper.getById(objectId);
        if (row == null || !"ACTIVE".equals(row.status())) {
            throw new ObjectStoreExceptions.ObjectNotFoundException(objectId);
        }
        return row;
    }

    private DataObjectVersionRow requireActiveVersion(String objectId) {
        DataObjectVersionRow row = versionMapper.getActive(objectId);
        if (row == null) {
            throw new ObjectStoreExceptions.NoActiveVersionException(objectId);
        }
        return row;
    }

    private DataObjectVersionRow requireVersion(String objectId, int version) {
        DataObjectVersionRow row = versionMapper.get(objectId, version);
        if (row == null) {
            throw new ObjectStoreExceptions.ObjectNotFoundException(objectId + " v" + version);
        }
        return row;
    }

    /** ds_ 加 8 位十六进制小写（词表口径）；撞库重取，8 次仍撞视为异常。 */
    private String generateId() {
        for (int i = 0; i < 8; i++) {
            byte[] bytes = new byte[4];
            RANDOM.nextBytes(bytes);
            StringBuilder sb = new StringBuilder("ds_");
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            if (objectMapper.getById(sb.toString()) == null) {
                return sb.toString();
            }
        }
        throw new IllegalStateException("对象 id 生成连续撞库，请重试");
    }

    private static String maxCoverageEnd(String oldEnd, String dataEnd) {
        if (dataEnd == null) {
            return oldEnd;
        }
        if (oldEnd == null || oldEnd.isBlank() || dataEnd.compareTo(oldEnd) > 0) {
            return dataEnd;
        }
        return oldEnd;
    }

    private DataObjectRow toRow(RegisterCommand cmd, String id, long now) {
        return new DataObjectRow(id, cmd.name(), cmd.description(), cmd.sourceType(), cmd.owner(), cmd.visibility(),
                Jsons.writeRefs(cmd.semanticRefs()), cmd.coverageScope(), cmd.frequency(), cmd.genMethod(),
                cmd.genTask() == null ? "" : cmd.genTask(), Jsons.writeStringArray(cmd.inputs()),
                Jsons.writeStringArray(cmd.primaryKey()), cmd.adjustment(), cmd.ccy(), 1,
                cmd.exportFormat() == null || cmd.exportFormat().isEmpty() ? "csv" : cmd.exportFormat(),
                cmd.compression() == null || cmd.compression().isEmpty() ? "none" : cmd.compression(),
                "ACTIVE", now, now);
    }

    private DataObject toDomain(DataObjectRow row) {
        return new DataObject(row.id(), row.name(), row.description(),
                world.willfrog.datafrog.server.objectdata.domain.SourceType.fromValue(row.sourceType()),
                row.owner(),
                world.willfrog.datafrog.server.objectdata.domain.Visibility.fromValue(row.visibility()),
                Jsons.readRefs(row.semanticRefsJson()), row.coverageScope(),
                world.willfrog.datafrog.server.objectdata.domain.Frequency.fromValue(row.frequency()),
                world.willfrog.datafrog.server.objectdata.domain.GenMethod.fromValue(row.genMethod()),
                row.genTask(), Jsons.readStringArray(row.inputsJson()),
                Jsons.readStringArray(row.primaryKeyJson()),
                row.adjustment() == null ? null
                        : world.willfrog.datafrog.server.objectdata.domain.Adjustment.fromValue(row.adjustment()),
                row.ccy() == null ? null
                        : world.willfrog.datafrog.server.objectdata.domain.Ccy.fromValue(row.ccy()),
                row.currentVersion(), row.exportFormat(), row.compression(), row.status(),
                row.createdAt(), row.updatedAt());
    }
}
