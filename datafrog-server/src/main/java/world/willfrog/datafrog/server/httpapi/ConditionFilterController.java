package world.willfrog.datafrog.server.httpapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.datafrog.server.conditionfilter.ConditionFilterService;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * 条件筛选的 HTTP 入口（014-data-05 方案 D3）。本轮只有执行端点：解析端点要新增大模型
 * 聊天调用，等 frog 对密钥用途扩展点头后才编码（方案待拍板项第一件）。执行端点收
 * 调用方确认过的条件清单原文——确认动作由调用方持有，服务端无草稿状态。
 *
 * 请求体三个键（camelCase，与 02/05 既有 HTTP 体一致）：conditions（条件清单，每条
 * semanticName / operator / value / 可选 timeScope{from,to}）、symbolPool（股票池）、
 * 可选 asOf（数据口径日期，缺省为各对象最新覆盖）。
 *
 * 应答两个键：hits（命中标的，每条带命中明细）与 misses（未命中标的，逐条带原因——
 * 方案要求逐条如实标注，原因类别：条件不成立、对象不可用、覆盖日期不足、断言未过、
 * 挂靠对象不唯一或没有挂靠对象；不写「对象不可见」）。
 */
@RestController
@RequestMapping(SessionAuthFilter.API_PREFIX)
public class ConditionFilterController {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final ConditionFilterService conditionFilterService;
    private final ErrorMapping errorMapping;
    private final ObjectMapper objectMapper;

    public ConditionFilterController(ConditionFilterService conditionFilterService,
                                     ErrorMapping errorMapping,
                                     ObjectMapper objectMapper) {
        this.conditionFilterService = conditionFilterService;
        this.errorMapping = errorMapping;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /condition-filters/execute。请求级问题（池子缺失、清单缺失、要素空白、
     * 日期格式）按 400 拒绝；对象级与标的级问题全部落应答的 misses 原因，不转成
     * HTTP 错误——用户要的是逐条结论，不是整体失败。
     */
    @PostMapping("/condition-filters/execute")
    public JsonNode execute(@RequestBody ConditionFilterService.ExecuteCommand command,
                            HttpServletRequest request) {
        String userId = AuthContext.requireUserId(request);
        validate(command);
        ConditionFilterService.ExecuteResult result = conditionFilterService.execute(userId, command);
        return objectMapper.valueToTree(result);
    }

    private void validate(ConditionFilterService.ExecuteCommand command) {
        // 需求硬性约束「只在用户明确给定的股票池内执行」：池子缺失或空直接 400，
        // 专用码 CONDITION_SCOPE_REQUIRED（方案定的码）
        if (command.symbolPool() == null || command.symbolPool().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONDITION_SCOPE_REQUIRED",
                    "symbolPool 缺失或为空：条件筛选只在用户明确给定的股票池内执行");
        }
        for (String symbol : command.symbolPool()) {
            if (symbol == null || symbol.isBlank()) {
                throw errorMapping.invalidArgument("symbolPool 含空白标的");
            }
        }
        if (command.conditions() == null || command.conditions().isEmpty()) {
            throw errorMapping.invalidArgument("conditions 缺失或为空");
        }
        for (ConditionFilterService.ConditionItem item : command.conditions()) {
            if (isBlank(item.semanticName()) || isBlank(item.operator()) || isBlank(item.value())) {
                throw errorMapping.invalidArgument(
                        "条件要素不完整（semanticName/operator/value 必填）: " + item);
            }
            // operator 合法域外的值不在请求级拒绝——方案写明「执行时按未命中处理」，
            // 未命中原因里如实写出不支持的算子
            if (item.timeScope() != null) {
                requireDate(item.timeScope().from(), "timeScope.from");
                requireDate(item.timeScope().to(), "timeScope.to");
            }
        }
        requireDate(command.asOf(), "asOf");
    }

    private void requireDate(String raw, String field) {
        if (raw != null && !raw.isBlank() && !DATE.matcher(raw.trim()).matches()) {
            throw errorMapping.invalidArgument(field + " 非法（需 YYYY-MM-DD）: " + raw);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
