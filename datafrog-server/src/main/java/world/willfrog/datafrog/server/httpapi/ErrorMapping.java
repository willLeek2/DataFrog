package world.willfrog.datafrog.server.httpapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import world.willfrog.datafrog.idl.ErrorCode;
import world.willfrog.datafrog.idl.ResultInfo;

/**
 * 内部错误码到 HTTP（状态码 + 机读业务码）的映射表，方案定的唯一映射处：
 * 01 信封（ResultInfo）的 ErrorCode 枚举与 02 应答的 error_code 字符串都逐条落在这里，
 * 控制器不做各自发挥；表内没有的码值按未识别故障回 500，并留日志，防止下层新增码值后
 * 这里静默透错。
 */
@Component
public class ErrorMapping {

    private static final Logger log = LoggerFactory.getLogger(ErrorMapping.class);

    /** 01 信封映射：result.ok=false 时按 code 逐条对应方案映射表。 */
    public ApiException fromResultInfo(ResultInfo result) {
        String detail = result.getMessage();
        return switch (result.getCode()) {
            case OBJECT_NOT_FOUND -> new ApiException(HttpStatus.NOT_FOUND, "OBJECT_NOT_FOUND", detail);
            case SEMANTIC_REF_NOT_FOUND -> new ApiException(HttpStatus.BAD_REQUEST, "SEMANTIC_REF_NOT_FOUND", detail);
            // DUPLICATE_VERSION 对外的业务码是 VERSION_CONFLICT：内部「版本已存在」与外部「版本冲突」同义
            case DUPLICATE_VERSION -> new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", detail);
            case NO_ACTIVE_VERSION -> new ApiException(HttpStatus.CONFLICT, "NO_ACTIVE_VERSION", detail);
            // INVALID_CONVENTION 对外的业务码是 CONVENTION_VIOLATION（登记内容违反已定约定）
            case INVALID_CONVENTION -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONVENTION_VIOLATION", detail);
            case DEFINITION_ALREADY_EXISTS -> new ApiException(HttpStatus.CONFLICT, "DEFINITION_ALREADY_EXISTS", detail);
            case TABLES_NOT_READY -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TABLES_NOT_READY", detail);
            case INVALID_ARGUMENT -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", detail);
            case INTERNAL -> internal();
            case OK -> {
                // 契约上不会发生（ok=false 才会进错误分支）；真发生了按未识别故障处理并留日志
                log.warn("ResultInfo ok=false 但 code=OK，按 INTERNAL 处理: message={}", detail);
                yield internal();
            }
            default -> unknown("ResultInfo", String.valueOf(result.getCode()));
        };
    }

    /**
     * 02 应答映射：create 的错误在 error_code 字段，get 的查询失败在 query_error_code 字段，
     * 两字段的码值空间相同，共用本方法。get 应答里任务终态自身的 error_code 不经过这里——
     * 那是任务资源本体信息，由控制器原样透出（见 GenerationTaskController）。
     */
    public ApiException fromTaskErrorCode(String errorCode, String message) {
        String detail = message == null ? "" : message;
        return switch (errorCode) {
            case "TASK_NOT_FOUND" -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", detail);
            case "UNAUTHENTICATED" -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", detail);
            case "CREDIT_INSUFFICIENT" -> new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_CREDITS", detail);
            case "INVALID_SPEC" -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", detail);
            case "GENERATION_IN_PROGRESS" -> new ApiException(HttpStatus.CONFLICT, "TASK_STATE_CONFLICT", detail);
            case "GENERATION_LAUNCH_FAILED" -> new ApiException(HttpStatus.BAD_GATEWAY, "RUN_LAUNCH_FAILED", detail);
            case "GENERATION_RUN_FAILED" -> new ApiException(HttpStatus.BAD_GATEWAY, "RUN_FAILED", detail);
            case "TASK_TIMEOUT" -> new ApiException(HttpStatus.CONFLICT, "TASK_TIMEOUT", detail);
            case "INTERNAL" -> internal();
            default -> unknown("task", errorCode);
        };
    }

    /** 参数类 400 的统一入口（查询参数值非法等，适配层自己做的参数校验）。 */
    public ApiException invalidArgument(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", detail);
    }

    /** INTERNAL 一律不透出内部说明，避免把下层异常文本泄漏给外部调用方。 */
    private static ApiException internal() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "内部错误");
    }

    private ApiException unknown(String source, String code) {
        log.warn("未识别的业务错误码按 INTERNAL 处理: source={} code={}", source, code);
        return internal();
    }
}
