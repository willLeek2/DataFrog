package world.willfrog.datafrog.server.httpapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器：全端口错误体统一 RFC 9457 的 problem+json（经 ProblemResponses 组装）。
 * 业务错误（ApiException）按方案映射表的状态码落；参数类协议错误（请求体解析失败、
 * 参数类型不匹配、缺必填参数）归 400 INVALID_ARGUMENT；Spring 其余协议错误
 * （405 方法不允许、406 不可接受、415 媒体类型不支持等）按各自状态码落，业务码取
 * 状态名的常量形态（协议码，方案业务码表之外）；未识别异常一律 500 INTERNAL，
 * 记 error 日志，对外只写「内部错误」，不暴露堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemResponses problemResponses;

    public GlobalExceptionHandler(ProblemResponses problemResponses) {
        this.problemResponses = problemResponses;
    }

    /** 已识别的业务错误：状态码与业务码原样落。 */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException e) {
        return problemResponses.build(e.getStatus(), e.getCode(), e.getDetail());
    }

    /** 请求体不是合法 JSON（或 body 缺失）：归 400，说明透出解析失败原文，客户端可自查。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleNotReadable(HttpMessageNotReadableException e) {
        String detail = e.getMessage() == null ? "请求体不是合法 JSON" : e.getMessage();
        return problemResponses.build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", detail);
    }

    /** 参数类型不匹配（如 limit=abc）：归 400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problemResponses.build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                "参数 " + e.getName() + " 类型不匹配: " + e.getValue());
    }

    /** 缺少必填参数：归 400。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException e) {
        return problemResponses.build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage());
    }

    /** 路径不存在（404）：方案业务码表之外，为保持全端口错误体形态一致而补充。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNotFound(NoResourceFoundException e) {
        return problemResponses.build(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "路径不存在: " + e.getResourcePath());
    }

    /**
     * Spring 其余协议错误的总收拢（405 方法不允许、406 不可接受、415 媒体类型不支持等，
     * 它们都继承 ResponseStatusException）：状态码沿用 Spring 的判定，业务码取状态名的
     * 常量形态。排在 ApiException 与各具体异常之后，只接收前面处理器没认领的。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleFrameworkError(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        String code = status != null ? status.name() : "HTTP_" + e.getStatusCode().value();
        String detail = e.getReason() != null ? e.getReason() : e.getClass().getSimpleName();
        return problemResponses.build(e.getStatusCode(), code, detail);
    }

    /** 未识别异常：记 error 日志（含堆栈，便于排查），对外只说「内部错误」。 */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnhandled(Exception e) {
        log.error("HTTP 入口未捕获异常", e);
        return problemResponses.build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "内部错误");
    }
}
