package world.willfrog.datafrog.server.httpapi;

import org.springframework.http.HttpStatusCode;

/**
 * HTTP 适配层内部的业务错误载体：HTTP 状态码 + 机读业务码 + 给调用方看的说明。
 * 控制器与可见性骨架抛它，由 GlobalExceptionHandler 统一落成 problem+json。
 * 它只表达已识别的失败；未识别异常由全局异常处理器另行收拢成 500，不经此类。
 */
public class ApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;
    private final String detail;

    public ApiException(HttpStatusCode status, String code, String detail) {
        super(code + ": " + detail);
        this.status = status;
        this.code = code;
        this.detail = detail == null ? "" : detail;
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getDetail() {
        return detail;
    }
}
