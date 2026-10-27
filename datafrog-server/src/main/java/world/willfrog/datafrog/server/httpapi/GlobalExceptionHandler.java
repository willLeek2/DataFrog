package world.willfrog.datafrog.server.httpapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 全局异常处理器：全端口错误体统一 RFC 9457 的 problem+json（经 ProblemResponses 组装）。
 *
 * <p>分三层：业务错误（ApiException）按方案映射表的状态码与业务码落；Spring MVC 协议错误
 * （400 参数类、404 路径不存在、405 方法不允许、406 不可接受、415 媒体类型不支持等）交给
 * 基类 ResponseEntityExceptionHandler——Spring 6 里这批异常各自继承不同父类、共同点是实现
 * ErrorResponse 接口，基类为它们逐一备好了处理器并原生产出 ProblemDetail，本类在
 * handleExceptionInternal 里把业务码与 title 归一成统一形态；未识别异常一律 500 INTERNAL，
 * 记 error 日志，对外只写「内部错误」，不暴露堆栈。</p>
 *
 * <p>协议错误的业务码口径：400 归 INVALID_ARGUMENT（与方案映射表一致），其余取状态名的
 * 常量形态（如 NOT_FOUND、METHOD_NOT_ALLOWED），属方案业务码表之外的协议层码值。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ProblemResponses problemResponses;

    public GlobalExceptionHandler(ProblemResponses problemResponses) {
        this.problemResponses = problemResponses;
    }

    /** 已识别的业务错误：状态码与业务码原样落，附带的扩展属性（如失败任务的 taskId）一并透出。 */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException e) {
        ProblemDetail body = problemResponses.build(e.getStatus(), e.getCode(), e.getDetail());
        e.getProperties().forEach(body::setProperty);
        return body;
    }

    /**
     * Spring MVC 协议错误的统一收口：基类各具体处理器都会走这里组装应答。
     * 把 ProblemDetail 归一成与业务错误同形态（title 与扩展属性 code 都填业务码），
     * detail 保留 Spring 给出的原文（参数名、路径等排查信息都在里面）。
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            String code = statusCode.value() == HttpStatus.BAD_REQUEST.value()
                    ? "INVALID_ARGUMENT"
                    : codeNameOf(statusCode);
            problem.setTitle(code);
            problem.setProperty("code", code);
            return new ResponseEntity<>(problem, headers, statusCode);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static String codeNameOf(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "HTTP_" + statusCode.value();
    }

    /** 未识别异常：记 error 日志（含堆栈，便于排查），对外只说「内部错误」。 */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnhandled(Exception e) {
        log.error("HTTP 入口未捕获异常", e);
        return problemResponses.build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "内部错误");
    }
}
