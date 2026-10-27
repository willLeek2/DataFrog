package world.willfrog.datafrog.server.conditionfilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import world.willfrog.datafrog.server.httpapi.VisibilityGuard;
import world.willfrog.datafrog.server.objectdata.domain.AssertionStatus;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectMapper;
import world.willfrog.datafrog.server.objectdata.service.ContentStorageService;
import world.willfrog.datafrog.server.objectdata.service.DataObjectRegistryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 条件筛选的执行段（014-data-05 方案 D3）。改前没有：需求把「一句话条件解析成结构化清单、
 * 用户确认、确认后按口径查询并计算」描述成三段动作，方案落成两个端点，本类承担执行端点的
 * 求值；解析端点要新增大模型聊天调用，等 frog 对密钥用途扩展点头后才编码，与本段互不阻塞。
 *
 * 执行语义按方案五条（bob 必写句展开）：
 * - 语义名找对象用字段等值匹配（04 过滤组的 filter_field_name 同一条 SQL），不是名字前缀；
 *   同一个数据对象在一次执行里只读取一次内容，再在读到的内容上对整个股票池求值。
 * - 一个语义名挂到多个对当前用户可见的对象时，相关标的未命中，原因「挂靠对象不唯一」。
 * - 缺省的口径日期用该对象的 coverage_end；断言未过或覆盖不到该日期的未命中，
 *   不把有空洞的日子当成有值。
 * - 算子本轮写死一组：等于、大于、大于等于、小于、小于等于；清单外的算子按未命中处理。
 * - 可见性逐对象生效，但 frog 对「存在但不可见回 403 还是 404」拍板之前，未命中原因
 *   不写「对象不可见」，与「没有挂靠对象」同一句表述——不给人拿股票池探测对象是否存在。
 *
 * 方案没逐字写到的读法（解释性决定，送节点 c 审查时逐条列明）：
 * - 列映射：semantic_name 与数据的列名等值——parquet 列名即写入时 CSV 表头列名
 *   （writePart 原样保留），与「语义定义就是字段级语义单元」的 04 注释同口径。
 * - 标的列：对象登记的主键首列（01 契约既有字段，不自造列名约定）；主键空或首列不在
 *   表头，该对象上所有标的未命中。
 * - 日期列：表头中名为 trade_date 或 date 的首列（主仓库数据集惯例列名）；条件带
 *   time_scope 而对象没有日期列时，相关标的未命中。
 * - 命中判定：条件在标的行集合（time_scope 过滤后）里存在满足的行即算该条件满足；
 *   命中值取满足行里日期列最大（最新）一行的值，没有日期列时取首个满足行。
 * - 断言状态：FAIL 与 PARTIAL 算「断言未过」；NO_ASSERTIONS 没有断言可谈未过，放行。
 * - 读取上限 256MB：全量读进内存求值的进程保护，不是产品口径；超出按对象级失败如实
 *   报原因，将来需要大对象时改流式求值。
 */
@Service
public class ConditionFilterService {

    private static final Logger log = LoggerFactory.getLogger(ConditionFilterService.class);

    /** 执行端点单对象读取上限（字节）：全量进内存求值的进程保护，超限对象按级失败。 */
    private static final long MAX_OBJECT_BYTES = 256L * 1024 * 1024;

    /** 本轮写死的五个算子（方案原文写法，中文词）。 */
    private static final String OP_EQ = "等于";
    private static final String OP_GT = "大于";
    private static final String OP_GTE = "大于等于";
    private static final String OP_LT = "小于";
    private static final String OP_LTE = "小于等于";

    /** 执行请求（HTTP 反序列化用 Jackson，键名 camelCase 与 02/05 既有 HTTP 体一致）。 */
    public record TimeScope(String from, String to) {
    }

    public record ConditionItem(String semanticName, String operator, String value, TimeScope timeScope) {
    }

    public record ExecuteCommand(List<ConditionItem> conditions, List<String> symbolPool, String asOf) {
    }

    /** 命中明细：命中时每个条件给一行满足记录。 */
    public record MatchedValue(String semanticName, String value, String date) {
    }

    public record Hit(String symbol, List<MatchedValue> matched) {
    }

    public record Miss(String symbol, String reason) {
    }

    public record ExecuteResult(List<Hit> hits, List<Miss> misses) {
    }

    /** 一个语义名解析出的对象与其数据快照；级失败时只带原因。 */
    private record ResolvedObject(String failureReason, DataObjectRegistryService.CardView card,
                                  ObjectDataSnapshot data) {
    }

    /** 对象全量数据快照：表头列名、按列名取值的行集合、标的列与日期列的列号（-1=不存在）。 */
    private record ObjectDataSnapshot(List<String> header, List<String[]> rows,
                                      int symbolColumn, int dateColumn) {
    }

    private final DataObjectRegistryService registry;
    private final ContentStorageService contentStore;
    private final VisibilityGuard visibilityGuard;

    public ConditionFilterService(DataObjectRegistryService registry, ContentStorageService contentStore,
                                  VisibilityGuard visibilityGuard) {
        this.registry = registry;
        this.contentStore = contentStore;
        this.visibilityGuard = visibilityGuard;
    }

    /**
     * 执行一次条件筛选。请求级校验（池子非空、清单非空、as_of 格式）归 controller，
     * 这里只做业务求值；所有对象级与标的级的问题都落 misses 原因，不抛 HTTP 错误——
     * 「逐条如实标注」是方案对 misses 的要求。
     */
    public ExecuteResult execute(String userId, ExecuteCommand cmd) {
        // 语义名去重解析：同一个语义名只查一次对象，结果给所有引用它的条件共用
        Map<String, ResolvedObject> resolved = new LinkedHashMap<>();
        for (ConditionItem item : cmd.conditions()) {
            if (!resolved.containsKey(item.semanticName())) {
                resolved.put(item.semanticName(), resolveObject(userId, item.semanticName(), cmd.asOf()));
            }
        }

        List<Hit> hits = new ArrayList<>();
        List<Miss> misses = new ArrayList<>();
        // 股票池按集合语义处理：重复标的只求值一次、只出一条结论（保首次出现顺序）
        for (String symbol : new java.util.LinkedHashSet<>(cmd.symbolPool())) {
            evaluateSymbol(symbol, cmd.conditions(), resolved, hits, misses);
        }
        return new ExecuteResult(List.copyOf(hits), List.copyOf(misses));
    }

    /**
     * 按语义名解析挂靠对象（字段等值，进程内走服务层过滤面），先过可见性再数个数：
     * 零个或多个都算解析失败。可见性滤掉的不单独表述（方案：不写「对象不可见」）。
     */
    private ResolvedObject resolveObject(String userId, String semanticName, String asOf) {
        List<DataObjectRegistryService.CardView> visible = new ArrayList<>();
        DataObjectMapper.ListFilters filters = new DataObjectMapper.ListFilters(
                null, semanticName, null, null, null, null, null);
        for (DataObjectRegistryService.CardView card : registry.list(filters, null, Integer.MAX_VALUE, 0).cards()) {
            if (visibilityGuard.isVisible(card.object().owner(), userId)) {
                visible.add(card);
            }
        }
        if (visible.isEmpty()) {
            return new ResolvedObject("没有挂靠对象", null, null);
        }
        if (visible.size() > 1) {
            return new ResolvedObject("挂靠对象不唯一", null, null);
        }
        DataObjectRegistryService.CardView card = visible.get(0);
        try {
            String objectFailure = checkObjectUsable(card, asOf);
            if (objectFailure != null) {
                return new ResolvedObject(objectFailure, card, null);
            }
            return new ResolvedObject(null, card, readSnapshot(card));
        } catch (Exception e) {
            // 数据读取故障如实进原因，不升格成 HTTP 5xx：标的是否命中本来就是逐条结论
            log.warn("条件筛选读取对象数据失败 objectId={}: {}", card.object().id(), e.getMessage());
            return new ResolvedObject("对象数据读取失败: " + e.getMessage(), card, null);
        }
    }

    /**
     * 对象级前置检查（freshness 与断言，04 的服务实现进程内直调；一次 freshness 同时
     * 拿断言汇总与覆盖末端）：断言 FAIL/PARTIAL 未过；as_of 非空时要求 coverage_end
     * 达到该日期（缺省口径=各对象最新覆盖，天然满足不比较）。返回失败原因，null=可用。
     */
    private String checkObjectUsable(DataObjectRegistryService.CardView card, String asOf) {
        DataObjectRegistryService.FreshnessView freshness = registry.freshness(card.object().id());
        if (freshness.assertionStatus() == AssertionStatus.FAIL
                || freshness.assertionStatus() == AssertionStatus.PARTIAL) {
            return "断言未过";
        }
        if (asOf != null && !asOf.isBlank()) {
            String coverageEnd = freshness.coverageEnd();
            if (coverageEnd == null || coverageEnd.isBlank() || coverageEnd.compareTo(asOf) < 0) {
                return "覆盖日期不足";
            }
        }
        return null;
    }

    /**
     * 读对象全量内容并解析成快照（bob 节点 c b672fb4e 裁定的读取方式）：readObjectData
     * 翻页读全（页不跨 part），按 partIndex 分组把同一 part 的页拼回该 part 的完整字节；
     * 全部 part 按序拼接算 sha256，与版本行 content_checksum（写入时全部 part 按文件名
     * 升序拼接的 sha256，partIndex 升序即文件名升序）核对——对不上说明读取不全或对象在
     * 读取间隙被改动，该对象失败不求值，不出错误结论；对上之后经存储层把 parquet 逐
     * part 解成表（入库 part 是 parquet，不是 CSV 文本，不能按文本猜）。标的列=主键首列，
     * 日期列=trade_date/date 首个命中。
     */
    private ObjectDataSnapshot readSnapshot(DataObjectRegistryService.CardView card) {
        java.util.TreeMap<Integer, java.io.ByteArrayOutputStream> parts = new java.util.TreeMap<>();
        String pageToken = "";
        long totalBytes = 0;
        do {
            DataObjectRegistryService.DataPageView page = registry.readObjectData(
                    card.object().id(), 0, pageToken, 0);
            totalBytes += page.data().length;
            if (totalBytes > MAX_OBJECT_BYTES) {
                throw new IllegalStateException(
                        "对象数据超出条件筛选读取上限 " + (MAX_OBJECT_BYTES / 1024 / 1024) + "MB");
            }
            parts.computeIfAbsent(page.partIndex(), k -> new java.io.ByteArrayOutputStream())
                    .writeBytes(page.data());
            pageToken = page.nextPageToken();
        } while (!pageToken.isBlank());

        List<byte[]> ordered = new ArrayList<>();
        for (java.io.ByteArrayOutputStream buffer : parts.values()) {
            ordered.add(buffer.toByteArray());
        }
        String actual = sha256Hex(ordered);
        if (!actual.equalsIgnoreCase(card.contentChecksum())) {
            throw new IllegalStateException("对象内容校验不符（读到的字节与版本行 content_checksum "
                    + "不一致，可能读取不全或对象已被改动），不求值: " + card.object().id());
        }
        ContentStorageService.ParquetRows table = contentStore.readParquetRows(ordered);
        List<String> header = table.header();

        List<String> primaryKey = card.object().primaryKey();
        String keyColumn = primaryKey == null || primaryKey.isEmpty() ? null : primaryKey.get(0);
        int symbolColumn = keyColumn == null ? -1 : header.indexOf(keyColumn);
        int dateColumn = -1;
        for (String candidate : List.of("trade_date", "date")) {
            dateColumn = header.indexOf(candidate);
            if (dateColumn >= 0) {
                break;
            }
        }
        return new ObjectDataSnapshot(header, table.rows(), symbolColumn, dateColumn);
    }

    /** 逐 part 流式算 sha256（与版本行 content_checksum 的拼接定义同口径，不整读）。 */
    private static String sha256Hex(List<byte[]> partsInOrder) {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        for (byte[] part : partsInOrder) {
            digest.update(part);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** 单个标的的求值：逐条件推进，首个不满足即记 miss；全部满足记 hit。 */
    private void evaluateSymbol(String symbol, List<ConditionItem> conditions,
                                Map<String, ResolvedObject> resolved, List<Hit> hits, List<Miss> misses) {
        List<MatchedValue> matched = new ArrayList<>();
        for (ConditionItem item : conditions) {
            ResolvedObject object = resolved.get(item.semanticName());
            if (object.failureReason() != null) {
                misses.add(new Miss(symbol, item.semanticName() + ": " + object.failureReason()));
                return;
            }
            String failure = evaluateCondition(symbol, item, object);
            if (failure != null) {
                misses.add(new Miss(symbol, failure));
                return;
            }
            matched.add(latestMatch(symbol, item, object));
        }
        hits.add(new Hit(symbol, List.copyOf(matched)));
    }

    /**
     * 单条件求值：标的行集合（time_scope 过滤后）里存在满足行=通过。返回失败原因或 null。
     */
    private String evaluateCondition(String symbol, ConditionItem item, ResolvedObject object) {
        ObjectDataSnapshot data = object.data();
        String operator = item.operator();
        if (!isSupportedOperator(operator)) {
            return "条件不成立: " + item.semanticName() + " 算子不支持（本轮写死: 等于/大于/大于等于/小于/小于等于）: "
                    + operator;
        }
        if (data.symbolColumn() < 0) {
            return item.semanticName() + ": 对象没有可用的标的列（主键首列不在数据表头）";
        }
        // 行集合先按标的列筛
        List<String[]> symbolRows = new ArrayList<>();
        for (String[] row : data.rows()) {
            if (symbol.equals(cellAt(row, data.symbolColumn()))) {
                symbolRows.add(row);
            }
        }
        if (symbolRows.isEmpty()) {
            return "条件不成立: 标的 " + symbol + " 在对象里没有行";
        }
        // time_scope 再筛：日期列不存在而条件带了范围=无法求值，如实报
        if (item.timeScope() != null && (item.timeScope().from() != null || item.timeScope().to() != null)) {
            if (data.dateColumn() < 0) {
                return item.semanticName() + ": 对象没有日期列，条件带时间范围无法求值";
            }
            symbolRows = filterByTimeScope(symbolRows, data.dateColumn(), item.timeScope());
            if (symbolRows.isEmpty()) {
                return "条件不成立: 标的 " + symbol + " 在时间范围内没有行";
            }
        }
        int valueColumn = data.header().indexOf(item.semanticName());
        if (valueColumn < 0) {
            return item.semanticName() + ": 数据表头里没有这个语义名的列";
        }
        for (String[] row : symbolRows) {
            if (compareSatisfied(cellAt(row, valueColumn), operator, item.value())) {
                return null;
            }
        }
        return "条件不成立: " + item.semanticName() + " " + operator + " " + item.value();
    }

    /** 命中明细：满足行里日期列最大的那行（没有日期列取首个满足行）。 */
    private MatchedValue latestMatch(String symbol, ConditionItem item, ResolvedObject object) {
        ObjectDataSnapshot data = object.data();
        int valueColumn = data.header().indexOf(item.semanticName());
        String[] best = null;
        for (String[] row : data.rows()) {
            if (!symbol.equals(cellAt(row, data.symbolColumn()))) {
                continue;
            }
            if (item.timeScope() != null && data.dateColumn() >= 0
                    && !inTimeScope(cellAt(row, data.dateColumn()), item.timeScope())) {
                continue;
            }
            if (compareSatisfied(cellAt(row, valueColumn), item.operator(), item.value())) {
                if (best == null || (data.dateColumn() >= 0
                        && normalizeDate(cellAt(row, data.dateColumn()))
                        .compareTo(normalizeDate(cellAt(best, data.dateColumn()))) > 0)) {
                    best = row;
                }
            }
        }
        String date = best == null || data.dateColumn() < 0 ? null
                : normalizeDate(cellAt(best, data.dateColumn()));
        return new MatchedValue(item.semanticName(), best == null ? null : cellAt(best, valueColumn), date);
    }

    /** 行集合的 time_scope 过滤（闭区间，YYYY-MM-DD 字典序比较；行值先归一化）。 */
    private List<String[]> filterByTimeScope(List<String[]> rows, int dateColumn, TimeScope scope) {
        List<String[]> kept = new ArrayList<>();
        for (String[] row : rows) {
            if (inTimeScope(cellAt(row, dateColumn), scope)) {
                kept.add(row);
            }
        }
        return kept;
    }

    private boolean inTimeScope(String cellValue, TimeScope scope) {
        String date = normalizeDate(cellValue);
        if (date == null) {
            return false;
        }
        if (scope.from() != null && !scope.from().isBlank() && date.compareTo(scope.from()) < 0) {
            return false;
        }
        if (scope.to() != null && !scope.to().isBlank() && date.compareTo(scope.to()) > 0) {
            return false;
        }
        return true;
    }

    /**
     * 日期值归一化成 YYYY-MM-DD：本形态直接用；8 位无杠补杠；13 位毫秒时间戳换算
     * （主仓库数据集存在这两种历史形态，台账 S2 记录过）；其余形态回 null（不可比）。
     */
    private static String normalizeDate(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return v;
        }
        if (v.matches("\\d{8}")) {
            return v.substring(0, 4) + "-" + v.substring(4, 6) + "-" + v.substring(6, 8);
        }
        if (v.matches("\\d{13}")) {
            return java.time.Instant.ofEpochMilli(Long.parseLong(v))
                    .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .toLocalDate().toString();
        }
        return null;
    }

    private static boolean isSupportedOperator(String operator) {
        return OP_EQ.equals(operator) || OP_GT.equals(operator) || OP_GTE.equals(operator)
                || OP_LT.equals(operator) || OP_LTE.equals(operator);
    }

    /**
     * 算子比较：两边都能解成数值时按数值比较（金融字段的正常形态）；「等于」额外接受
     * 字符串等值（文本类字段）；大小类算子遇到不可数值化的值按不满足处理，由调用方
     * 统一记「条件不成立」。
     */
    private static boolean compareSatisfied(String cellValue, String operator, String expected) {
        if (cellValue == null) {
            return false;
        }
        Double left = tryDouble(cellValue.trim());
        Double right = tryDouble(expected == null ? null : expected.trim());
        if (left != null && right != null) {
            return switch (operator) {
                case OP_EQ -> left.doubleValue() == right.doubleValue();
                case OP_GT -> left > right;
                case OP_GTE -> left >= right;
                case OP_LT -> left < right;
                case OP_LTE -> left <= right;
                default -> false;
            };
        }
        if (OP_EQ.equals(operator)) {
            return cellValue.trim().equals(expected == null ? null : expected.trim());
        }
        return false;
    }

    private static Double tryDouble(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 行数组越界防御：短行缺的列当 null。 */
    private static String cellAt(String[] row, int column) {
        if (column < 0 || column >= row.length) {
            return null;
        }
        return row[column];
    }

}