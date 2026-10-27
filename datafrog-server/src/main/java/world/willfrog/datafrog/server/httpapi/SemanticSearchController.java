package world.willfrog.datafrog.server.httpapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectRow;
import world.willfrog.datafrog.server.semanticindex.SemanticIndexService;
import world.willfrog.datafrog.server.semanticindex.SemanticIndexService.SemanticHit;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 语义查询端点（014-data-05 方案 D2）：按自然语言提问匹配数据对象描述，返回候选对象。
 * GET 而不是 POST：查询幂等只读、无副作用；查询文本经 URL 编码传输是标准做法。
 *
 * 执行序：search(q, limit 的两倍) → 按命中 object_id 批量回表查登记卡 → 逐卡过滤
 * （状态 ACTIVE 且可见性经 VisibilityGuard）→ 截取前 limit 条。多取一倍再过滤，是因为
 * 可见性过滤会淘汰一部分命中；过滤后剩下的不足请求条数时，就返回这不足的一页，不为
 * 凑数继续翻向量库（bob 裁定）。
 *
 * 可见性以数据库登记卡为准，不用向量点载荷里的 owner/visibility 快照——载荷是写入
 * 时刻的快照，登记卡才是事实来源，两处可能漂移（方案 D2 裁定）。
 *
 * score 原样透出（Qdrant 余弦距离，越大越相似），不设相似度截断——阈值多少合适没有
 * 数据支撑，验收期观察分布后再定（开放问题第二条）。
 */
@RestController
@RequestMapping(SessionAuthFilter.API_PREFIX)
public class SemanticSearchController {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final SemanticIndexService semanticIndexService;
    private final DataObjectMapper dataObjectMapper;
    private final VisibilityGuard visibilityGuard;
    private final ErrorMapping errorMapping;
    private final ObjectMapper objectMapper;

    public SemanticSearchController(SemanticIndexService semanticIndexService,
                                    DataObjectMapper dataObjectMapper,
                                    VisibilityGuard visibilityGuard,
                                    ErrorMapping errorMapping,
                                    ObjectMapper objectMapper) {
        this.semanticIndexService = semanticIndexService;
        this.dataObjectMapper = dataObjectMapper;
        this.visibilityGuard = visibilityGuard;
        this.errorMapping = errorMapping;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/semantic-search")
    public JsonNode search(@RequestParam String q,
                           @RequestParam(required = false) Integer limit,
                           HttpServletRequest request) {
        String userId = AuthContext.requireUserId(request);
        if (q == null || q.isBlank()) {
            throw errorMapping.invalidArgument("q（查询文本）不能为空");
        }
        // limit 口径照抄 DataObjectController 的 normalizeLimit：非正取缺省 10，超上限截断到 50
        int effectiveLimit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        // 查询路径只读索引、不写索引，不存在查询触发写入失败的问题
        List<SemanticHit> hits;
        try {
            hits = semanticIndexService.search(q, effectiveLimit * 2);
        } catch (IllegalStateException e) {
            // embedding 或 Qdrant 调用失败的既定口径（方案 D2）
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SEMANTIC_SEARCH_UNAVAILABLE",
                    "语义检索暂不可用: " + e.getMessage());
        }

        // 批量回表：命中 id 一次取回，不逐条查库
        List<String> hitIds = hits.stream().map(SemanticHit::objectId).distinct().toList();
        Map<String, DataObjectRow> rowsById = hitIds.isEmpty() ? Map.of()
                : dataObjectMapper.getByIds(hitIds).stream()
                        .collect(Collectors.toMap(DataObjectRow::id, Function.identity()));

        ArrayNode items = objectMapper.createArrayNode();
        int taken = 0;
        for (SemanticHit hit : hits) {
            if (taken >= effectiveLimit) {
                break;
            }
            DataObjectRow row = rowsById.get(hit.objectId());
            if (row == null || !"ACTIVE".equals(row.status())) {
                continue;
            }
            if (!visibilityGuard.isVisible(row.owner(), userId)) {
                continue;
            }
            ObjectNode item = items.addObject();
            item.put("objectId", row.id());
            item.put("name", row.name());
            item.put("description", row.description());
            item.put("score", hit.score());
            item.put("owner", row.owner());
            item.put("visibility", row.visibility());
            item.put("currentVersion", row.currentVersion());
            item.put("coverageScope", row.coverageScope());
            taken++;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.set("items", items);
        return body;
    }
}
