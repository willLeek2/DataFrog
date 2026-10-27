package world.willfrog.datafrog.server.httpapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.util.JsonFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.datafrog.idl.DeleteDataObjectRequest;
import world.willfrog.datafrog.idl.DeleteDataObjectResponse;
import world.willfrog.datafrog.idl.ExportDataObjectRequest;
import world.willfrog.datafrog.idl.ExportDataObjectResponse;
import world.willfrog.datafrog.idl.ExportShard;
import world.willfrog.datafrog.idl.GetDataObjectRequest;
import world.willfrog.datafrog.idl.GetDataObjectResponse;
import world.willfrog.datafrog.idl.ListDataObjectsRequest;
import world.willfrog.datafrog.idl.ListDataObjectsResponse;
import world.willfrog.datafrog.server.maintenance.ExportRateLimitedException;
import world.willfrog.datafrog.server.maintenance.ExportRateLimiter;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectMapper;
import world.willfrog.datafrog.server.objectdata.service.DataObjectRegistryService;
import world.willfrog.datafrog.server.rpc.DataObjectServiceImpl;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据对象的 HTTP 入口：列表、详情、删除、导出、分片下载。进程内直调 01 的 bean（不经 Dubbo）。
 *
 * 应答原则：01 信封 ok=true 透出 proto 转 JSON 的业务字段，ok=false 按方案映射表落成
 * problem+json。可见性骨架（VisibilityGuard）在详情/删除/导出/下载前判定，列表逐张过滤。
 *
 * total 的取舍：proto 列表应答是有意设计的纯分页枚举（不带总数），HTTP 列表另经服务层
 * 旁路取过滤后计数（014-data-05 方案 D4 起 total 与 nextPageToken 都按过滤后口径）；
 * 卡片本身仍全部经 01 的信封与 proto 契约，不绕开。
 */
@RestController
@RequestMapping(SessionAuthFilter.API_PREFIX)
public class DataObjectController {

    /** 分页口径单一来源是 01 的公开常量，缺省页大小与页大小上限不在两处维护。 */
    private static final int DEFAULT_LIMIT = DataObjectServiceImpl.DEFAULT_PAGE_SIZE;
    private static final int MAX_LIMIT = DataObjectServiceImpl.MAX_PAGE_SIZE;

    private final DataObjectServiceImpl dataObjectService;
    private final DataObjectRegistryService registry;
    private final VisibilityGuard visibilityGuard;
    private final ErrorMapping errorMapping;
    private final ExportFileSupport exportFiles;
    private final ExportRateLimiter exportRateLimiter;
    private final ObjectMapper objectMapper;

    public DataObjectController(DataObjectServiceImpl dataObjectService,
                                DataObjectRegistryService registry,
                                VisibilityGuard visibilityGuard,
                                ErrorMapping errorMapping,
                                ExportFileSupport exportFiles,
                                ExportRateLimiter exportRateLimiter,
                                ObjectMapper objectMapper) {
        this.dataObjectService = dataObjectService;
        this.registry = registry;
        this.visibilityGuard = visibilityGuard;
        this.errorMapping = errorMapping;
        this.exportFiles = exportFiles;
        this.exportRateLimiter = exportRateLimiter;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /data-objects?limit=&offset=&filter_...=（014-data-05 方案 D4）。
     * limit 缺省 20、上限 200（口径照抄 01 的 pageSizeOf：非正取缺省，超上限截断）；
     * offset 是十进制起始位，缺省 0，非十进制或负数归 400。翻译成 proto 的 page_size /
     * page_token（offset 字符串，首页空串）。
     *
     * 过滤参数七个，参数名与 proto 字段名一致，全部可选：filter_semantic_name、
     * filter_field_name、filter_coverage_scope_substring、filter_coverage_start_from、
     * filter_coverage_end_to、filter_freshness_since、filter_assertion_status。
     * filter_owner 不接受调用方传入（bob 必写句）：请求里出现该参数名直接 400，
     * 服务端固定填当前登录用户——所以七个参数都留空时 HTTP 列表也只列这个用户
     * 自己的对象，与 01 的全库分页不同；全库分页行为保留给进程内不带属主条件的
     * 内部调用。HTTP 面与主仓库工具路径同口径（属主过滤是平台对自己列表的约束）。
     *
     * total 与 nextPageToken 都按过滤后计数（bob 必写句）：nextPageToken 直接透传
     * proto 应答（rpc 层已按「offset+本页条数<过滤后总数」推进）；total 经旁路一次
     * 服务层过滤列表取 page.total（proto 应答是有意设计的纯分页枚举、不带总数，
     * 旁路 limit=1 只为拿计数；带断言过滤时计数经两段式现算，同样准确）。
     */
    @GetMapping("/data-objects")
    public JsonNode list(@RequestParam(required = false) Integer limit,
                         @RequestParam(required = false) String offset,
                         @RequestParam(required = false) String filter_semantic_name,
                         @RequestParam(required = false) String filter_field_name,
                         @RequestParam(required = false) String filter_coverage_scope_substring,
                         @RequestParam(required = false) String filter_coverage_start_from,
                         @RequestParam(required = false) String filter_coverage_end_to,
                         @RequestParam(required = false) String filter_freshness_since,
                         @RequestParam(required = false) String filter_assertion_status,
                         HttpServletRequest request) throws IOException {
        String userId = AuthContext.requireUserId(request);
        if (request.getParameterMap().containsKey("filter_owner")) {
            throw errorMapping.invalidArgument(
                    "filter_owner 是服务端固定注入的条件，不接受调用方传入");
        }
        int size = normalizeLimit(limit);
        int off = parseOffset(offset);
        world.willfrog.datafrog.server.objectdata.domain.AssertionStatus statusFilter =
                parseAssertionStatus(filter_assertion_status);
        ListDataObjectsRequest protoRequest = ListDataObjectsRequest.newBuilder()
                .setPageSize(size)
                .setPageToken(off == 0 ? "" : String.valueOf(off))
                .setFilterSemanticName(orEmpty(filter_semantic_name))
                .setFilterFieldName(orEmpty(filter_field_name))
                .setFilterCoverageScopeSubstring(orEmpty(filter_coverage_scope_substring))
                .setFilterCoverageStartFrom(orEmpty(filter_coverage_start_from))
                .setFilterCoverageEndTo(orEmpty(filter_coverage_end_to))
                .setFilterFreshnessSince(orEmpty(filter_freshness_since))
                .setFilterAssertionStatus(statusFilter == null
                        ? world.willfrog.datafrog.idl.AssertionStatus.ASSERTION_STATUS_UNKNOWN
                        : world.willfrog.datafrog.idl.AssertionStatus.valueOf(
                                "ASSERTION_STATUS_" + statusFilter.name()))
                // 服务端固定注入当前登录用户（D4：HTTP 列表只列自己的对象）
                .setFilterOwner(userId)
                .build();
        ListDataObjectsResponse response = dataObjectService.listDataObjects(protoRequest);
        if (!response.getResult().getOk()) {
            throw errorMapping.fromResultInfo(response.getResult());
        }
        ArrayNode items = objectMapper.createArrayNode();
        for (var card : response.getCardsList()) {
            // 属主已在 SQL 层过滤，这里保留逐卡判定作为将来可见性档位扩展的接缝
            // （骨架期唯一规则仍是 owner 相等，注入属主后恒可见）
            if (!visibilityGuard.isVisible(card.getOwner(), userId)) {
                continue;
            }
            items.add(objectMapper.readTree(JsonFormat.printer().print(card)));
        }
        // total 旁路：与上面同一组过滤条件取一次服务层列表，只用它的过滤后计数
        DataObjectMapper.ListFilters filters = new DataObjectMapper.ListFilters(
                filter_semantic_name, filter_field_name, filter_coverage_scope_substring,
                filter_coverage_start_from, filter_coverage_end_to, filter_freshness_since, userId);
        long total = registry.list(filters, statusFilter, 1, 0).total();
        ObjectNode body = objectMapper.createObjectNode();
        body.set("items", items);
        body.put("total", total);
        if (!response.getNextPageToken().isBlank()) {
            body.put("nextPageToken", response.getNextPageToken());
        }
        return body;
    }

    /** GET /data-objects/{id}：详情。不可见归 403 FORBIDDEN_VISIBILITY，与方案映射表一致。 */
    @GetMapping("/data-objects/{objectId}")
    public JsonNode get(@PathVariable String objectId, HttpServletRequest request) throws IOException {
        String userId = AuthContext.requireUserId(request);
        GetDataObjectResponse response = dataObjectService.getDataObject(
                GetDataObjectRequest.newBuilder().setObjectId(objectId).build());
        if (!response.getResult().getOk()) {
            throw errorMapping.fromResultInfo(response.getResult());
        }
        visibilityGuard.requireVisible(response.getCard().getOwner(), userId);
        return objectMapper.readTree(JsonFormat.printer().print(response.getCard()));
    }

    /**
     * DELETE /data-objects/{id}：软删。
     * 方案新增规则，审查时确认：删除仅限属主。骨架期可见性唯一规则就是 owner 相等，
     * requireVisible 在这里同时充当删除的属主校验；将来可见性档位扩展后（如系统来源
     * 全员可见），此处必须单独保留属主判断，不能再复用可见性结论。
     */
    @DeleteMapping("/data-objects/{objectId}")
    public ResponseEntity<Void> delete(@PathVariable String objectId, HttpServletRequest request) {
        String userId = AuthContext.requireUserId(request);
        // 先取登记卡：既做可见性/属主判定，也让不存在的对象在删除前得到 404 而不是张冠李戴
        GetDataObjectResponse existing = dataObjectService.getDataObject(
                GetDataObjectRequest.newBuilder().setObjectId(objectId).build());
        if (!existing.getResult().getOk()) {
            throw errorMapping.fromResultInfo(existing.getResult());
        }
        visibilityGuard.requireVisible(existing.getCard().getOwner(), userId);
        DeleteDataObjectResponse response = dataObjectService.deleteDataObject(
                DeleteDataObjectRequest.newBuilder().setObjectId(objectId).build());
        if (!response.getResult().getOk()) {
            throw errorMapping.fromResultInfo(response.getResult());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /data-objects/{id}/export?version=：触发导出并回分片清单。
     * version 缺省或非正时取登记卡 current_version（正常流程即 ACTIVE 行）。不回传服务端的
     * export_dir（服务器内部路径不下发），每片改附本端点的下载路径。
     *
     * 导出触发限额（014-data-05 方案 D8）：每用户每日次数与同对象冷却间隔（热配置起始值
     * 50 次 / 60 秒），超限 429 RATE_LIMITED（06 预留码由 05 产出）。限额判断放在可见性
     * 判定之后——不可见对象的请求连额度都不该消耗；触发即计数，导出随后成败都算一次。
     * 分片下载通道（downloadShard）的「文件不在就补一次导出」不另计触发（方案只把本
     * 端点定义为触发面）。
     *
     * 已知边界（审查 P2 记账，接受现状）：本端点按方案字面每次触发全量重导出（清空目录重写），
     * 与下载通道的「文件已在就跳过」不完全对称；并发下「下载在读、导出清空重写」有竞态窗口，
     * 已打开句柄能读完，但「校验存在→打开」之间可能 FileNotFoundException 落成 500。
     * 窗口小、影响低，将来如需收紧再做「清单齐整就跳过重导出」。
     */
    @GetMapping("/data-objects/{objectId}/export")
    public JsonNode export(@PathVariable String objectId,
                           @RequestParam(required = false) Integer version,
                           HttpServletRequest request) throws IOException {
        String userId = AuthContext.requireUserId(request);
        GetDataObjectResponse existing = dataObjectService.getDataObject(
                GetDataObjectRequest.newBuilder().setObjectId(objectId).build());
        if (!existing.getResult().getOk()) {
            throw errorMapping.fromResultInfo(existing.getResult());
        }
        visibilityGuard.requireVisible(existing.getCard().getOwner(), userId);
        try {
            exportRateLimiter.tryAcquire(userId, objectId);
        } catch (ExportRateLimitedException e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", e.getMessage());
        }
        int effectiveVersion = resolveVersion(version, existing.getCard().getCurrentVersion());
        ExportDataObjectResponse response = dataObjectService.exportDataObject(
                ExportDataObjectRequest.newBuilder().setObjectId(objectId).setVersion(effectiveVersion).build());
        if (!response.getResult().getOk()) {
            throw errorMapping.fromResultInfo(response.getResult());
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("version", effectiveVersion);
        body.put("shardCount", response.getShardCount());
        ArrayNode shards = body.putArray("shards");
        for (ExportShard shard : response.getShardsList()) {
            ObjectNode item = shards.addObject();
            item.put("fileName", shard.getFileName());
            item.put("byteSize", shard.getByteSize());
            item.put("sha256", shard.getSha256());
            item.put("downloadUrl", downloadUrl(objectId, effectiveVersion, shard.getFileName()));
        }
        body.put("aggregateChecksum", response.getAggregateChecksum());
        return body;
    }

    /**
     * GET /data-objects/{id}/export/shards/{fileName}?version=：分片下载通道。
     * 文件已存在就直接流式回（同对象同版本导出落同一目录，避免每次下载都重复全量导出）；
     * 不存在才触发一次导出再定位。字段说明附表（field_notes.csv）随导出落同一目录，
     * 同样可经本通道取；aggregate checksum 不落盘成文件（只在导出应答 JSON 里），
     * 没有对应的下载物。
     */
    @GetMapping("/data-objects/{objectId}/export/shards/{fileName}")
    public void downloadShard(@PathVariable String objectId,
                              @PathVariable String fileName,
                              @RequestParam(required = false) Integer version,
                              HttpServletRequest request,
                              HttpServletResponse response) throws IOException {
        String userId = AuthContext.requireUserId(request);
        exportFiles.requireSafeFileName(fileName);
        GetDataObjectResponse existing = dataObjectService.getDataObject(
                GetDataObjectRequest.newBuilder().setObjectId(objectId).build());
        if (!existing.getResult().getOk()) {
            throw errorMapping.fromResultInfo(existing.getResult());
        }
        visibilityGuard.requireVisible(existing.getCard().getOwner(), userId);
        int effectiveVersion = resolveVersion(version, existing.getCard().getCurrentVersion());
        Path file = exportFiles.resolveShardFile(objectId, effectiveVersion, fileName);
        if (!Files.isRegularFile(file)) {
            if (Files.isDirectory(exportFiles.exportDirOf(objectId, effectiveVersion))) {
                // 导出目录在而文件不在 = 文件名不在本次导出清单里（客户端笔误或拿了旧清单），
                // 回 404；不触发无谓的全量导出（大对象时是重 IO）再回 500
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "本次导出清单里没有这个文件: " + fileName);
            }
            ExportDataObjectResponse exported = dataObjectService.exportDataObject(
                    ExportDataObjectRequest.newBuilder().setObjectId(objectId).setVersion(effectiveVersion).build());
            if (!exported.getResult().getOk()) {
                throw errorMapping.fromResultInfo(exported.getResult());
            }
        }
        if (!Files.isRegularFile(file)) {
            // 刚导出完仍定位不到 = 文件名本就不在清单里，同样归 404 而不是 500
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                    "本次导出清单里没有这个文件: " + fileName);
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(exportFiles.contentTypeFor(fileName).toString());
        response.setContentLengthLong(Files.size(file));
        // fileName 被字符白名单约束为纯 ASCII，Content-Disposition 里无需再做文件名编码
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(response.getOutputStream());
        }
    }

    /** proto3 builder 的 setString 不接受 null，HTTP 参数缺省统一转空串。 */
    private static String orEmpty(String raw) {
        return raw == null ? "" : raw;
    }

    /**
     * filter_assertion_status 的枚举解析：缺省、空串、UNKNOWN 都表示不加断言条件（回 null）；
     * 合法值 NO_ASSERTIONS/PASS/PARTIAL/FAIL（domain 枚举即此短名，与 proto 枚举同名去前缀）；
     * 带 ASSERTION_STATUS_ 前缀的长写同样接受（与 proto 字段值形态一致）；其余值 400。
     */
    private world.willfrog.datafrog.server.objectdata.domain.AssertionStatus parseAssertionStatus(String raw) {
        if (raw == null || raw.isBlank() || "UNKNOWN".equals(raw.trim())
                || "ASSERTION_STATUS_UNKNOWN".equals(raw.trim())) {
            return null;
        }
        String candidate = raw.trim();
        if (candidate.startsWith("ASSERTION_STATUS_")) {
            candidate = candidate.substring("ASSERTION_STATUS_".length());
        }
        try {
            return world.willfrog.datafrog.server.objectdata.domain.AssertionStatus.valueOf(candidate);
        } catch (IllegalArgumentException e) {
            throw errorMapping.invalidArgument(
                    "filter_assertion_status 非法（NO_ASSERTIONS/PASS/PARTIAL/FAIL）: " + raw);
        }
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int parseOffset(String offset) {
        if (offset == null || offset.isBlank()) {
            return 0;
        }
        try {
            long value = Long.parseLong(offset.trim());
            if (value < 0 || value > Integer.MAX_VALUE) {
                throw new NumberFormatException(offset);
            }
            return (int) value;
        } catch (NumberFormatException e) {
            throw errorMapping.invalidArgument("offset 非法（需非负十进制整数）: " + offset);
        }
    }

    /** version 缺省或非正时取登记卡当前版本；显式正数原样使用。 */
    private static int resolveVersion(Integer version, int currentVersion) {
        return version == null || version <= 0 ? currentVersion : version;
    }

    private static String downloadUrl(String objectId, int version, String fileName) {
        return SessionAuthFilter.API_PREFIX + "/data-objects/" + objectId
                + "/export/shards/" + fileName + "?version=" + version;
    }
}
