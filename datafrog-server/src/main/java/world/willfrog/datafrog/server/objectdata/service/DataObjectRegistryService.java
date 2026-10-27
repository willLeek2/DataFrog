package world.willfrog.datafrog.server.objectdata.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import world.willfrog.datafrog.server.objectdata.domain.AssertionSpec;
import world.willfrog.datafrog.server.objectdata.domain.AssertionStatus;
import world.willfrog.datafrog.server.objectdata.domain.CatalogAction;
import world.willfrog.datafrog.server.objectdata.domain.DataObject;
import world.willfrog.datafrog.server.objectdata.domain.SemanticRef;
import world.willfrog.datafrog.server.objectdata.repo.CatalogRecordMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectRow;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectVersionMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectVersionRow;
import world.willfrog.datafrog.server.semanticindex.SemanticIndexService;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
            byte[] csvContent,
            List<AssertionSpec> assertions) {
    }

    /** 登记卡视图：对象行 + ACTIVE 版本行数据覆盖四项的读取时投影 + 断言汇总现算（014-data-04）。 */
    public record CardView(DataObject object, long rowCount, String coverageStart, String coverageEnd,
                           String contentChecksum, AssertionStatus assertionStatus) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(DataObjectRegistryService.class);

    /** ReadObjectData 单页字节上限（M4 服务端钳制常量，1MB：请求超出取上限值）。 */
    static final int MAX_READ_PAGE_BYTES = 1024 * 1024;

    /** depends_on 递归深度上限（M2：深度到 10 仍需递归时只判那一条不通过）。 */
    private static final int MAX_DEPENDS_DEPTH = 10;

    /** 三个日期过滤条件的格式从轻校验（YYYY-MM-DD：库内 TEXT 日期同字典序即时间序，按字符串比较）。 */
    private static final Pattern FILTER_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final DataObjectMapper objectMapper;
    private final DataObjectVersionMapper versionMapper;
    private final CatalogRecordMapper catalogMapper;
    private final SemanticDefinitionService definitionService;
    private final ContentStorageService contentStore;
    private final ConventionValidator validator;
    private final SemanticIndexService semanticIndex;
    private final TransactionTemplate tx;

    public DataObjectRegistryService(DataObjectMapper objectMapper, DataObjectVersionMapper versionMapper,
                                     CatalogRecordMapper catalogMapper, SemanticDefinitionService definitionService,
                                     ContentStorageService contentStore, ConventionValidator validator,
                                     SemanticIndexService semanticIndex,
                                     PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.versionMapper = versionMapper;
        this.catalogMapper = catalogMapper;
        this.definitionService = definitionService;
        this.contentStore = contentStore;
        this.validator = validator;
        this.semanticIndex = semanticIndex;
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
        // 语义索引写入点 REGISTERED（014-data-06 方案 D6）：元数据事务成功后才写向量
        upsertIndexQuietly(id, cmd.name(), cmd.description(), cmd.owner(), cmd.visibility());
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
        // 追加不写语义索引（014-data-06 方案 D6）：APPENDED 只动数据覆盖日期不动描述，
        // 重写向量是浪费
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
        // 语义索引写入点 MODIFIED（014-data-06 方案 D6）：当前升版不改名字与描述，
        // 本次写入是同内容幂等刷新；挂在这里是为了将来出现改描述的升版路径时不用补挂点
        upsertIndexQuietly(objectId, object.name(), object.description(), object.owner(), object.visibility());
        return get(objectId);
    }

    public void delete(String objectId) {
        requireVisible(objectId);
        long now = System.currentTimeMillis();
        tx.executeWithoutResult(status -> {
            objectMapper.markDeleted(objectId, now);
            catalogMapper.insert(objectId, CatalogAction.DELETED.name(), "system", "软删（保留策略任务前不物理删）", now);
        });
        // 删除后的索引清理由 014-data-05 的删除保留策略经 SemanticIndexService.deleteByObjectId
        // 统一接走，本条删除路径不直接调（方案 D6）
    }

    public CardView get(String objectId) {
        DataObjectRow object = requireVisible(objectId);
        DataObjectVersionRow active = requireActiveVersion(objectId);
        return new CardView(toDomain(object), active.rowCount(), active.coverageStart(),
                active.coverageEnd(), active.contentChecksum(), assertionStatusOf(objectId));
    }

    public record ListPage(List<CardView> cards, long total) {
    }

    public ListPage list(int limit, int offset) {
        List<CardView> cards = new ArrayList<>();
        for (DataObjectRow row : objectMapper.listActive(limit, offset)) {
            cards.add(cardOf(row, new HashMap<>()));
        }
        return new ListPage(cards, objectMapper.countActive());
    }

    /**
     * 过滤枚举（014-data-04，M1 两段式）。无断言状态过滤（statusFilter=null）时走 SQL
     * LIMIT/OFFSET 直出；有断言状态过滤时 SQL 不做 OFFSET——按其余条件取候选（按 id 序），
     * 逐对象 assertionStatusOf 现算滤完，再按 page_token 偏移量切页（token 仍是过滤后流的偏移量，
     * 不编码过滤条件）。同一请求内 depends_on 链上的对象按 object_id 记忆化，避免重复求值。
     */
    public ListPage list(DataObjectMapper.ListFilters filters, AssertionStatus statusFilter, int limit, int offset) {
        validateFilterDates(filters);
        Map<String, AssertionStatus> memo = new HashMap<>();
        if (statusFilter == null) {
            List<CardView> cards = new ArrayList<>();
            for (DataObjectRow row : objectMapper.listFiltered(filters, limit, offset)) {
                cards.add(cardOf(row, memo));
            }
            return new ListPage(cards, objectMapper.countFiltered(filters));
        }
        List<CardView> matched = new ArrayList<>();
        for (DataObjectRow row : objectMapper.listFiltered(filters, null, 0)) {
            CardView card = cardOf(row, memo);
            if (card.assertionStatus() == statusFilter) {
                matched.add(card);
            }
        }
        int from = Math.min(offset, matched.size());
        int to = Math.min(from + limit, matched.size());
        return new ListPage(List.copyOf(matched.subList(from, to)), matched.size());
    }

    /** 单行组卡：数据覆盖四项投影 + 断言汇总现算（依赖链结论走同一份 memo）。 */
    private CardView cardOf(DataObjectRow row, Map<String, AssertionStatus> memo) {
        DataObjectVersionRow active = versionMapper.getActive(row.id());
        if (active == null) {
            throw new ObjectStoreExceptions.NoActiveVersionException(row.id());
        }
        return new CardView(toDomain(row), active.rowCount(), active.coverageStart(), active.coverageEnd(),
                active.contentChecksum(), statusOf(row.id(), new HashSet<>(), 0, memo, null));
    }

    /** 断言单条现算结果（spec 原样带回；actual/reason 供详单展示）。 */
    public record AssertionOutcome(AssertionSpec spec, boolean passed, String actual, String reason) {
    }

    /** 断言详单应答视图（GetAvailabilityAssertions）：逐条现算结果 + 汇总。 */
    public record AssertionsView(AssertionStatus status, List<AssertionOutcome> details) {
    }

    /** 断言汇总现算入口（M2）：卡片投影与 freshness 摘要用；逐条详单走 {@link #assertionsOf}。 */
    public AssertionStatus assertionStatusOf(String objectId) {
        return statusOf(objectId, new HashSet<>(), 0, new HashMap<>(), null);
    }

    public AssertionsView assertionsOf(String objectId) {
        requireVisible(objectId);
        requireActiveVersion(objectId);
        List<AssertionOutcome> details = new ArrayList<>();
        AssertionStatus status = statusOf(objectId, new HashSet<>(), 0, new HashMap<>(), details);
        return new AssertionsView(status, details);
    }

    /** freshness 应答视图（M3）。note 文案按 D3 统一句；日期有值且无空洞时为空串。 */
    public record FreshnessView(String coverageEnd, String continuousCompleteDate, boolean hasGapAfterWatermark,
                                String note, AssertionStatus assertionStatus) {
    }

    /**
     * freshness 查询（M3 三种情形）。连续完整日两列由 03 写入、04 只读：
     * 两列都空→日期空、has_gap 假、note「还没有连续完整日」；日期有值且 has_gap 假→返回日期、
     * 不附补数说明；日期有值且 has_gap 真→note 说明下一轮从连续完整日的下一个开市日起补
     * （消费方按 01302 的开市日日历自算具体日，应答不带推算出的补数日）。
     */
    public FreshnessView freshness(String objectId) {
        requireVisible(objectId);
        DataObjectVersionRow active = requireActiveVersion(objectId);
        String date = active.continuousCompleteDate();
        boolean gap = Boolean.TRUE.equals(active.hasGapAfterWatermark());
        String note;
        if (date == null || date.isBlank()) {
            gap = false;
            date = "";
            note = "还没有连续完整日";
        } else if (gap) {
            note = "连续完整日之后有空洞，下一轮从连续完整日的下一个开市日起补";
        } else {
            note = "";
        }
        return new FreshnessView(active.coverageEnd() == null ? "" : active.coverageEnd(), date, gap, note,
                assertionStatusOf(objectId));
    }

    /** ReadObjectData 单页应答视图：partIndex 为 part-%06d 的 1 起序号（按文件名升序）；nextPageToken 空串=读完。 */
    public record DataPageView(byte[] data, String nextPageToken, int partIndex, boolean partBoundaryEnd) {
    }

    /**
     * 按版本读已入库内容的一段字节（M4，对象数据进沙箱的主通道）。只读不写：不重算数据覆盖事实、
     * 不触碰导出目录（导出仍走 ExportDataObject）。version 空/0 解析为当前 ACTIVE 版本；
     * page_size 服务端钳制（<=0 取默认、超出取上限，均 {@link #MAX_READ_PAGE_BYTES}）；
     * 游标编码 (part_index, byte_offset)；part 按文件名升序（与版本行 content_checksum 的拼接
     * 定义同序）；一页不跨 part——取 min(剩余页上限, 当前 part 剩余字节)，页尾恰在 part 末尾时
     * partBoundaryEnd=true（调用方据此把流拆回 part 文件集合）。
     */
    public DataPageView readObjectData(String objectId, int versionParam, String pageToken, int pageSize) {
        requireVisible(objectId);
        DataObjectVersionRow row = versionParam <= 0 ? requireActiveVersion(objectId)
                : requireVersion(objectId, versionParam);
        int size = pageSize <= 0 ? MAX_READ_PAGE_BYTES : Math.min(pageSize, MAX_READ_PAGE_BYTES);
        List<String> parts = contentStore.listPartFileNames(row.contentPath());
        long[] cursor = decodeReadCursor(pageToken);
        int partIndex = (int) cursor[0];
        long byteOffset = cursor[1];
        // 防御：游标落在 part 末尾或空 part 上时顺移到下一个 part（本服务签发的游标不会出现这种形态）
        while (partIndex <= parts.size()
                && contentStore.partSize(row.contentPath(), parts.get(partIndex - 1)) <= byteOffset) {
            partIndex++;
            byteOffset = 0;
        }
        if (partIndex > parts.size()) {
            return new DataPageView(new byte[0], "", 0, false);
        }
        String partFile = parts.get(partIndex - 1);
        long partSize = contentStore.partSize(row.contentPath(), partFile);
        int take = (int) Math.min((long) size, partSize - byteOffset);
        byte[] data = contentStore.readPartRange(row.contentPath(), partFile, byteOffset, take);
        boolean boundaryEnd = byteOffset + data.length >= partSize;
        String next = boundaryEnd && partIndex >= parts.size() ? ""
                : encodeReadCursor(boundaryEnd ? partIndex + 1 : partIndex,
                        boundaryEnd ? 0 : byteOffset + data.length);
        return new DataPageView(data, next, partIndex, boundaryEnd);
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

    /**
     * 语义索引写入的静默封装：元数据落库是权威事实，索引可以按台账重建，
     * 所以向量写入失败只记 error 日志留重建线索，不推翻已成功的登记或升版。
     */
    private void upsertIndexQuietly(String objectId, String name, String description,
                                    String owner, String visibility) {
        try {
            semanticIndex.upsert(objectId, name, description, owner, visibility);
        } catch (Exception e) {
            log.error("语义索引写入失败（元数据已落库不受影响，可按台账重建）objectId={}: {}",
                    objectId, e.getMessage());
        }
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

    /**
     * 断言汇总现算（M2 全语义），结果不落库。path 为当前依赖链上的对象集合（本方法内会把
     * 本对象加进去再评 depends_on，自依赖即环）；depth 为本对象在链上的深度（根=0）；
     * memo 为本请求内的按 object_id 记忆化（list 过滤与卡投影共用同一份）。
     * 未登记断言=NO_ASSERTIONS；各条独立判定后全过=PASS、部分=PARTIAL、全不过=FAIL。
     * details 非 null 时收根对象的逐条详单（GetAvailabilityAssertions 用）。
     */
    private AssertionStatus statusOf(String objectId, Set<String> path, int depth,
                                     Map<String, AssertionStatus> memo, List<AssertionOutcome> details) {
        AssertionStatus cached = memo.get(objectId);
        if (cached != null) {
            return cached;
        }
        DataObjectRow row = objectMapper.getById(objectId);
        if (row == null || !"ACTIVE".equals(row.status())) {
            // 依赖对象的存在性在单条判定层拦（dependency_missing）；这里是防御性拦截，按 FAIL 记忆化防重复穿透
            memo.put(objectId, AssertionStatus.FAIL);
            return AssertionStatus.FAIL;
        }
        List<AssertionSpec> specs = Jsons.readAssertions(row.assertionsJson());
        if (specs.isEmpty()) {
            memo.put(objectId, AssertionStatus.NO_ASSERTIONS);
            return AssertionStatus.NO_ASSERTIONS;
        }
        DataObjectVersionRow active = versionMapper.getActive(objectId);
        Set<String> down = new HashSet<>(path);
        down.add(objectId);
        boolean anyPass = false;
        boolean anyFail = false;
        for (AssertionSpec spec : specs) {
            AssertionOutcome outcome = evaluate(spec, active, down, depth, memo);
            if (outcome.passed()) {
                anyPass = true;
            } else {
                anyFail = true;
            }
            if (depth == 0 && details != null) {
                details.add(outcome);
            }
        }
        AssertionStatus status = anyPass ? (anyFail ? AssertionStatus.PARTIAL : AssertionStatus.PASS)
                : AssertionStatus.FAIL;
        memo.put(objectId, status);
        return status;
    }

    /**
     * 断言逐条判定。coverage_end_at_or_after 比 ACTIVE 版本 coverage_end（通过只说明覆盖终点到了
     * 指定日，不代表中间没有空洞——连续性看 freshness）；row_count_at_least 比 ACTIVE 版本
     * row_count；depends_on_objects_pass 逐个依赖对象递归汇总，任一依赖非 PASS 即本条不通过
     * （环=cycle_detected、超深=depth_limit、依赖缺失=dependency_missing，均只判这一条、
     * 同对象其余断言继续算）；认不出的类型=unknown_type。path 已含本对象。
     */
    private AssertionOutcome evaluate(AssertionSpec spec, DataObjectVersionRow active, Set<String> path,
                                      int depth, Map<String, AssertionStatus> memo) {
        String type = spec.type() == null ? "" : spec.type();
        switch (type) {
            case "coverage_end_at_or_after" -> {
                String end = active == null ? null : active.coverageEnd();
                boolean ok = end != null && !end.isBlank()
                        && spec.dateValue() != null && !spec.dateValue().isBlank()
                        && end.compareTo(spec.dateValue()) >= 0;
                return new AssertionOutcome(spec, ok, "coverage_end=" + (end == null ? "" : end),
                        ok ? "" : "coverage_end 未达到断言日");
            }
            case "row_count_at_least" -> {
                long actual = active == null ? 0 : active.rowCount();
                boolean ok = actual >= spec.countValue();
                return new AssertionOutcome(spec, ok, "row_count=" + actual, ok ? "" : "row_count 未达到断言值");
            }
            case "depends_on_objects_pass" -> {
                List<String> deps = spec.dependsOn() == null ? List.of() : spec.dependsOn();
                StringBuilder actual = new StringBuilder();
                String reason = "";
                boolean allPass = !deps.isEmpty();
                for (String dep : deps) {
                    if (actual.length() > 0) {
                        actual.append(", ");
                    }
                    if (path.contains(dep)) {
                        actual.append(dep).append("=CYCLE");
                        allPass = false;
                        if (reason.isEmpty()) {
                            reason = "cycle_detected";
                        }
                        continue;
                    }
                    DataObjectRow depRow = objectMapper.getById(dep);
                    if (depRow == null || !"ACTIVE".equals(depRow.status())
                            || versionMapper.getActive(dep) == null) {
                        actual.append(dep).append("=MISSING");
                        allPass = false;
                        if (reason.isEmpty()) {
                            reason = "dependency_missing";
                        }
                        continue;
                    }
                    if (depth >= MAX_DEPENDS_DEPTH) {
                        actual.append(dep).append("=DEPTH_LIMIT");
                        allPass = false;
                        if (reason.isEmpty()) {
                            reason = "depth_limit";
                        }
                        continue;
                    }
                    AssertionStatus depStatus = statusOf(dep, path, depth + 1, memo, null);
                    actual.append(dep).append("=").append(depStatus.name());
                    if (depStatus != AssertionStatus.PASS) {
                        allPass = false;
                        if (reason.isEmpty()) {
                            reason = "dependency_not_pass";
                        }
                    }
                }
                return new AssertionOutcome(spec, allPass, actual.toString(), allPass ? "" : reason);
            }
            default -> {
                return new AssertionOutcome(spec, false, "", "unknown_type");
            }
        }
    }

    /** 三个日期过滤条件的格式从轻校验；不合法直接 INVALID_ARGUMENT（与库内 TEXT 比较的口径一致）。 */
    private static void validateFilterDates(DataObjectMapper.ListFilters filters) {
        for (String d : new String[]{filters.coverageStartFrom(), filters.coverageEndTo(), filters.freshnessSince()}) {
            if (d != null && !d.isBlank() && !FILTER_DATE.matcher(d).matches()) {
                throw new ObjectStoreExceptions.InvalidArgumentException("日期过滤条件须为 YYYY-MM-DD: " + d);
            }
        }
    }

    /** ReadObjectData 游标：partIndex:byteOffset（如 "3:1048576"）；空/空白=首页 (1, 0)。 */
    private static long[] decodeReadCursor(String token) {
        if (token == null || token.isBlank()) {
            return new long[]{1, 0};
        }
        int colon = token.indexOf(':');
        if (colon <= 0) {
            throw new ObjectStoreExceptions.InvalidArgumentException("page_token 非法: " + token);
        }
        try {
            long part = Long.parseLong(token.substring(0, colon));
            long offset = Long.parseLong(token.substring(colon + 1));
            if (part < 1 || offset < 0) {
                throw new NumberFormatException(token);
            }
            return new long[]{part, offset};
        } catch (NumberFormatException e) {
            throw new ObjectStoreExceptions.InvalidArgumentException("page_token 非法: " + token);
        }
    }

    private static String encodeReadCursor(long partIndex, long byteOffset) {
        return partIndex + ":" + byteOffset;
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
                Jsons.writeStringArray(cmd.primaryKey()),
                Jsons.writeAssertions(cmd.assertions() == null ? List.of() : cmd.assertions()),
                cmd.adjustment(), cmd.ccy(), 1,
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
                Jsons.readAssertions(row.assertionsJson() == null ? "[]" : row.assertionsJson()),
                row.adjustment() == null ? null
                        : world.willfrog.datafrog.server.objectdata.domain.Adjustment.fromValue(row.adjustment()),
                row.ccy() == null ? null
                        : world.willfrog.datafrog.server.objectdata.domain.Ccy.fromValue(row.ccy()),
                row.currentVersion(), row.exportFormat(), row.compression(), row.status(),
                row.createdAt(), row.updatedAt());
    }
}
