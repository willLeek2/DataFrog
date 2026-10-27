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
import world.willfrog.datafrog.server.objectdata.repo.DataObjectMapper;
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
 * total 的取舍：proto 列表应答是有意设计的纯分页枚举（不带总数），HTTP 列表要回过滤前总数，
 * 只能旁路取一次 ACTIVE 对象计数；卡片本身仍全部经 01 的信封与 proto 契约，不绕开。
 */
@RestController
@RequestMapping(SessionAuthFilter.API_PREFIX)
public class DataObjectController {

    /** 分页口径单一来源是 01 的公开常量，缺省页大小与页大小上限不在两处维护。 */
    private static final int DEFAULT_LIMIT = DataObjectServiceImpl.DEFAULT_PAGE_SIZE;
    private static final int MAX_LIMIT = DataObjectServiceImpl.MAX_PAGE_SIZE;

    private final DataObjectServiceImpl dataObjectService;
    private final DataObjectMapper dataObjectMapper;
    private final VisibilityGuard visibilityGuard;
    private final ErrorMapping errorMapping;
    private final ExportFileSupport exportFiles;
    private final ObjectMapper objectMapper;

    public DataObjectController(DataObjectServiceImpl dataObjectService,
                                DataObjectMapper dataObjectMapper,
                                VisibilityGuard visibilityGuard,
                                ErrorMapping errorMapping,
                                ExportFileSupport exportFiles,
                                ObjectMapper objectMapper) {
        this.dataObjectService = dataObjectService;
        this.dataObjectMapper = dataObjectMapper;
        this.visibilityGuard = visibilityGuard;
        this.errorMapping = errorMapping;
        this.exportFiles = exportFiles;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /data-objects?limit=&offset=。limit 缺省 20、上限 200（口径照抄 01 的
     * pageSizeOf：非正取缺省，超上限截断）；offset 是十进制起始位，缺省 0，
     * 非十进制或负数归 400。翻译成 proto 的 page_size / page_token（offset 字符串，首页空串）。
     */
    @GetMapping("/data-objects")
    public JsonNode list(@RequestParam(required = false) Integer limit,
                         @RequestParam(required = false) String offset,
                         HttpServletRequest request) throws IOException {
        String userId = AuthContext.requireUserId(request);
        int size = normalizeLimit(limit);
        int off = parseOffset(offset);
        ListDataObjectsRequest protoRequest = ListDataObjectsRequest.newBuilder()
                .setPageSize(size)
                .setPageToken(off == 0 ? "" : String.valueOf(off))
                .build();
        ListDataObjectsResponse response = dataObjectService.listDataObjects(protoRequest);
        if (!response.getResult().getOk()) {
            throw errorMapping.fromResultInfo(response.getResult());
        }
        ArrayNode items = objectMapper.createArrayNode();
        for (var card : response.getCardsList()) {
            // 骨架期可见性唯一规则是 owner 相等；登记卡上的 visibility 档位语义待拍板，暂不参与判定
            if (!visibilityGuard.isVisible(card.getOwner(), userId)) {
                continue;
            }
            items.add(objectMapper.readTree(JsonFormat.printer().print(card)));
        }
        long total = dataObjectMapper.countActive();
        ObjectNode body = objectMapper.createObjectNode();
        body.set("items", items);
        // 过滤前总数（骨架期口径）：如实回未过滤的 ACTIVE 对象计数，不做「按可见条目凑数」的假分页
        body.put("total", total);
        // 翻页游标沿用 01 语义（存储侧偏移量）：按未过滤的本页条数推进，
        // 过滤只影响本页回多少张，不造成跳页或重页
        if (off + response.getCardsCount() < total) {
            body.put("nextPageToken", String.valueOf(off + response.getCardsCount()));
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
