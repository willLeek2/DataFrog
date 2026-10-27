package world.willfrog.datafrog.server.httpapi;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import world.willfrog.datafrog.server.task.ModificationTaskServiceImpl;
import world.willfrog.datafrog.server.task.ModificationTaskServiceImpl.CreateModificationTaskRequest;
import world.willfrog.datafrog.server.task.ModificationTaskServiceImpl.CreateModificationTaskResponse;
import world.willfrog.datafrog.server.task.ModificationTaskServiceImpl.GetModificationTaskRequest;

/**
 * 修改任务的 HTTP 入口（014-data-05 方案 D1）：POST 创建、GET 查询。进程内直调服务实现
 * （本轮不开远程契约，bob 裁定），形态镜像 GenerationTaskController。
 *
 * 应答原则：error_code 非空时不透业务字段，按 ErrorMapping 的修改任务映射表落成
 * problem+json；拉起失败（任务行已落库）经扩展属性带出 taskId 与 status，调用方拿得到
 * 任务号去查询核对（同 02）。get 的查询失败走 query_error_code 字段；任务终态自身的
 * error_code 是任务资源本体信息，原样留在 200 应答体里，不映射成 HTTP 错误。
 *
 * 身份的取舍：请求体不接收 session_token（客户端带了也忽略），一律用过滤器认证后
 * 存下的原始 token 回填——身份以服务端校验结果为准，不让客户端自报（同 02）。
 */
@RestController
@RequestMapping(SessionAuthFilter.API_PREFIX)
public class ModificationTaskController {

    private final ModificationTaskServiceImpl modificationTaskService;
    private final ErrorMapping errorMapping;
    private final ObjectMapper objectMapper;

    public ModificationTaskController(ModificationTaskServiceImpl modificationTaskService,
                                      ErrorMapping errorMapping,
                                      ObjectMapper objectMapper) {
        this.modificationTaskService = modificationTaskService;
        this.errorMapping = errorMapping;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/modification-tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode create(@RequestBody CreateTaskBody body, HttpServletRequest request) {
        // 空值回填空串：服务对空白的校验在受理逻辑内（INVALID_SPEC），适配层不提前发明规则
        CreateModificationTaskRequest serviceRequest = new CreateModificationTaskRequest(
                AuthContext.requireSessionToken(request),
                body.specJson() == null ? "" : body.specJson(),
                body.clientRequestId() == null ? "" : body.clientRequestId());
        CreateModificationTaskResponse response = modificationTaskService.createModificationTask(serviceRequest);
        if (response.errorCode() != null && !response.errorCode().isEmpty()) {
            ApiException error = errorMapping.fromModificationTaskErrorCode(response.errorCode(), response.errorMessage());
            if (response.taskId() != null) {
                // 服务契约保证 MODIFICATION_LAUNCH_FAILED 时任务行已落库、taskId 与 status
                // 一并返回——经 problem+json 扩展属性带出（同 02）
                error.withProperty("taskId", response.taskId())
                        .withProperty("status", response.status());
            }
            throw error;
        }
        return objectMapper.valueToTree(response);
    }

    @GetMapping("/modification-tasks/{taskId}")
    public JsonNode get(@PathVariable String taskId, HttpServletRequest request) {
        GetModificationTaskRequest serviceRequest = new GetModificationTaskRequest(
                AuthContext.requireSessionToken(request), taskId);
        var response = modificationTaskService.getModificationTask(serviceRequest);
        // 查询失败（未认证、任务不存在）落 HTTP 错误；任务终态的 error_code 留在 200 应答体
        if (response.queryErrorCode() != null && !response.queryErrorCode().isEmpty()) {
            throw errorMapping.fromModificationTaskErrorCode(response.queryErrorCode(), response.queryErrorMessage());
        }
        return objectMapper.valueToTree(response);
    }

    /**
     * 创建请求体：spec_json（含 02 三要素键与 05 新增的 base_object_id / base_version /
     * version_note 三键）与 client_request_id。主键名用 camelCase，同时用 @JsonAlias
     * （JSON 反序列化的字段别名机制）兼容 proto 风格的 snake_case 键名（同 02 适配层）。
     */
    record CreateTaskBody(
            @JsonAlias("spec_json") String specJson,
            @JsonAlias("client_request_id") String clientRequestId) {
    }
}
