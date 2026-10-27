package world.willfrog.datafrog.server.httpapi;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.datafrog.idl.CreateGenerationTaskRequest;
import world.willfrog.datafrog.idl.CreateGenerationTaskResponse;
import world.willfrog.datafrog.idl.GetGenerationTaskRequest;
import world.willfrog.datafrog.idl.GetGenerationTaskResponse;
import world.willfrog.datafrog.server.task.GenerationTaskServiceImpl;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * 生成任务的 HTTP 入口：POST 创建、GET 查询。进程内直调 02 的 bean（不经 Dubbo）。
 *
 * 应答原则：02 的 proto 消息原样转 JSON 透出（JsonFormat，proto 的标准 JSON 映射工具：
 * 字段名 camelCase、int64 按规范输出字符串形态、默认值字段省略）；error_code 非空时
 * 不透业务字段，按方案映射表落成 problem+json。
 *
 * 身份的取舍：请求体不接收 session_token（客户端带了也忽略），一律用过滤器认证后
 * 存下的原始 token 回填——身份以服务端校验结果为准，不让客户端自报。
 */
@RestController
@RequestMapping(SessionAuthFilter.API_PREFIX)
public class GenerationTaskController {

    private final GenerationTaskServiceImpl generationTaskService;
    private final ErrorMapping errorMapping;
    private final ObjectMapper objectMapper;

    public GenerationTaskController(GenerationTaskServiceImpl generationTaskService,
                                    ErrorMapping errorMapping,
                                    ObjectMapper objectMapper) {
        this.generationTaskService = generationTaskService;
        this.errorMapping = errorMapping;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/generation-tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode create(@RequestBody CreateTaskBody body, HttpServletRequest request) throws IOException {
        // 空值回填空串：02 对空白的校验在受理逻辑内（INVALID_SPEC），适配层不提前发明规则
        CreateGenerationTaskRequest protoRequest = CreateGenerationTaskRequest.newBuilder()
                .setSessionToken(AuthContext.requireSessionToken(request))
                .setTargetObjectName(body.targetObjectName() == null ? "" : body.targetObjectName())
                .setSpecJson(body.specJson() == null ? "" : body.specJson())
                .setClientRequestId(body.clientRequestId() == null ? "" : body.clientRequestId())
                .build();
        CreateGenerationTaskResponse response = generationTaskService.createGenerationTask(protoRequest);
        if (!response.getErrorCode().isEmpty()) {
            throw errorMapping.fromTaskErrorCode(response.getErrorCode(), response.getErrorMessage());
        }
        return toJson(response);
    }

    @GetMapping("/generation-tasks/{taskId}")
    public JsonNode get(@PathVariable String taskId, HttpServletRequest request) throws IOException {
        GetGenerationTaskRequest protoRequest = GetGenerationTaskRequest.newBuilder()
                .setSessionToken(AuthContext.requireSessionToken(request))
                .setTaskId(taskId)
                .build();
        GetGenerationTaskResponse response = generationTaskService.getGenerationTask(protoRequest);
        // get 的查询失败走 query_error_code 字段；任务终态自身的 error_code 是任务资源
        // 本体信息，原样留在 200 应答体里，不映射成 HTTP 错误
        if (!response.getQueryErrorCode().isEmpty()) {
            throw errorMapping.fromTaskErrorCode(response.getQueryErrorCode(), response.getQueryErrorMessage());
        }
        return toJson(response);
    }

    private JsonNode toJson(Message message) throws IOException {
        return objectMapper.readTree(JsonFormat.printer().print(message));
    }

    /**
     * 创建请求体：客户端给的字段以 proto 为准（target_object_name / spec_json /
     * client_request_id）。主键名用 camelCase（与本层导出应答等既有 JSON 形态一致），
     * 同时用 @JsonAlias（JSON 反序列化的字段别名机制）兼容 proto 风格的 snake_case 键名。
     */
    record CreateTaskBody(
            @JsonAlias("target_object_name") String targetObjectName,
            @JsonAlias("spec_json") String specJson,
            @JsonAlias("client_request_id") String clientRequestId) {
    }
}
