package world.willfrog.datafrog.server.httpapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.datafrog.server.maintenance.MaintenanceAccessGuard;
import world.willfrog.datafrog.server.maintenance.RetentionCheckRecorder;
import world.willfrog.datafrog.server.maintenance.SemanticIndexFailureCounter;
import world.willfrog.datafrog.server.maintenance.SemanticIndexRebuildService;
import world.willfrog.datafrog.server.semanticindex.SemanticIndexSettings;

/**
 * 维护端点（014-data-05 方案 D7/D9/D10）：留存核验查询、语义索引重建、索引失败计数
 * 状态。三端点共用名单鉴权（MaintenanceAccessGuard）：热配置
 * datafrog.maintenance.admin-user-ids 默认空名单=全部关闭（403 MAINTENANCE_FORBIDDEN），
 * 验收与生产要用时经热配置组下发名单。
 *
 * 重建正在进行时重复触发回 409 REBUILD_IN_PROGRESS（进程内原子标志，单实例口径）。
 */
@RestController
@RequestMapping(SessionAuthFilter.API_PREFIX)
public class MaintenanceController {

    private final MaintenanceAccessGuard accessGuard;
    private final RetentionCheckRecorder retentionCheckRecorder;
    private final SemanticIndexRebuildService rebuildService;
    private final SemanticIndexFailureCounter failureCounter;
    private final SemanticIndexSettings semanticIndexSettings;
    private final ErrorMapping errorMapping;
    private final ObjectMapper objectMapper;

    public MaintenanceController(MaintenanceAccessGuard accessGuard,
                                 RetentionCheckRecorder retentionCheckRecorder,
                                 SemanticIndexRebuildService rebuildService,
                                 SemanticIndexFailureCounter failureCounter,
                                 SemanticIndexSettings semanticIndexSettings,
                                 ErrorMapping errorMapping,
                                 ObjectMapper objectMapper) {
        this.accessGuard = accessGuard;
        this.retentionCheckRecorder = retentionCheckRecorder;
        this.rebuildService = rebuildService;
        this.failureCounter = failureCounter;
        this.semanticIndexSettings = semanticIndexSettings;
        this.errorMapping = errorMapping;
        this.objectMapper = objectMapper;
    }

    /**
     * 留存与清理的核验记录查询（方案 D7）：object_id 与 verdict（OK / VIOLATION）可选；
     * 异常显式标记=VIOLATION 可独立过滤，人工介入路径就是查这个端点。
     */
    @GetMapping("/maintenance/retention-checks")
    public JsonNode retentionChecks(@RequestParam(name = "object_id", required = false) String objectId,
                                    @RequestParam(required = false) String verdict,
                                    HttpServletRequest request) {
        accessGuard.requireAdmin(AuthContext.requireUserId(request));
        if (verdict != null && !verdict.isBlank() && !"OK".equals(verdict) && !"VIOLATION".equals(verdict)) {
            throw errorMapping.invalidArgument("verdict 只接受 OK 或 VIOLATION");
        }
        return objectMapper.valueToTree(retentionCheckRecorder.query(
                objectId == null || objectId.isBlank() ? null : objectId,
                verdict == null || verdict.isBlank() ? null : verdict));
    }

    /** 语义索引重建（方案 D9）：全量重写加孤儿对账两段，应答四个计数。 */
    @PostMapping("/maintenance/semantic-index/rebuild")
    public JsonNode rebuildIndex(HttpServletRequest request) {
        accessGuard.requireAdmin(AuthContext.requireUserId(request));
        return objectMapper.valueToTree(rebuildService.rebuild());
    }

    /**
     * 索引失败计数状态（方案 D9）：两计数为进程内即时快照（重启清零，长期趋势看日志），
     * 另带当前集合名与向量维度，便于核对重建目标。
     */
    @GetMapping("/maintenance/semantic-index/status")
    public JsonNode indexStatus(HttpServletRequest request) {
        accessGuard.requireAdmin(AuthContext.requireUserId(request));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("writeFailures", failureCounter.snapshot().get("writeFailures"));
        body.put("deleteFailures", failureCounter.snapshot().get("deleteFailures"));
        body.put("collection", semanticIndexSettings.getCollection());
        body.put("vectorDim", semanticIndexSettings.getEmbeddingDim());
        return body;
    }
}
