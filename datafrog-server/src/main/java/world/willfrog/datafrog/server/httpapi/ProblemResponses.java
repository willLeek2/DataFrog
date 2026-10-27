package world.willfrog.datafrog.server.httpapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 错误应答的统一出口。RFC 9457（HTTP 错误应答的标准 JSON 结构，媒体类型
 * application/problem+json）规定 type/title/status/detail/instance 五个标准键。
 * 过滤器（工作在 Spring MVC 渲染管线之外）与全局异常处理器共用本类，全端口错误体形态一致。
 *
 * 键位取舍：业务码放扩展属性 "code"；内部说明放标准字段 "detail"——
 * RFC 9457 标准键名本来就是 detail，直接用标准字段，避免另开同名扩展属性
 * 在序列化时与标准字段重名冲突。
 */
@Component
public class ProblemResponses {

    private final ObjectMapper objectMapper;

    public ProblemResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProblemDetail build(HttpStatusCode status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        // title 也填业务码：错误体自解释，调用方不用反查状态码与业务码的对照表
        body.setTitle(code);
        body.setDetail(detail);
        body.setProperty("code", code);
        return body;
    }

    /** 过滤器内直接写响应（此时没有 MVC 渲染管线可用，只能自己动手写应答体）。 */
    public void writeTo(HttpServletResponse response, ProblemDetail body) throws IOException {
        response.setStatus(body.getStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE + ";charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
