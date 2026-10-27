package world.willfrog.datafrog.server.httpapi;

import org.springframework.http.HttpStatusCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 适配层内部的业务错误载体：HTTP 状态码 + 机读业务码 + 给调用方看的说明。
 * 控制器与可见性骨架抛它，由 GlobalExceptionHandler 统一落成 problem+json。
 * 它只表达已识别的失败；未识别异常由全局异常处理器另行收拢成 500，不经此类。
 *
 * properties 是 problem+json 扩展属性（RFC 9457 允许标准键之外自定义键，本实现的
 * code 就是这么放的）的携带通道：个别错误需要带契约明文保证的字段（如创建任务
 * 失败时的 taskId/status）时用，映射表不因此加通用字段。
 */
public class ApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;
    private final String detail;
    private final Map<String, Object> properties = new LinkedHashMap<>();

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

    /** 附带一个 problem+json 扩展属性，返回自身供链式调用。 */
    public ApiException withProperty(String key, Object value) {
        this.properties.put(key, value);
        return this;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
