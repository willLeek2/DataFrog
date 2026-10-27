package world.willfrog.datafrog.server.httpapi;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import world.willfrog.datafrog.server.client.AuthValidationClient;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 登录态过滤器（014-data-06）：拦 /api/data-platform/v1/** 的全部请求。
 *
 * 取 token 的口径与主仓库 frontend 的 JwtAuthFilter 对齐：优先
 * Authorization: Bearer &lt;token&gt; 头，其次 ?token= 查询参数，不读 cookie。
 * 校验通过则把 userId 与原始 token 写进 request attribute 供控制器取；
 * 不通过（或根本没带 token）直接回 401 problem+json，不往下走——
 * 控制器因此可以假定前缀内的请求必然带着登录态。
 *
 * 过滤器按路径前缀生效：03/04/05 将来的端点落在同一前缀下即自动被覆盖，
 * 本轮不为它们建控制器，也无需改这里。
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    /** 对外路径前缀：边缘反向代理按此前缀把数据平台请求送到本端口（见 application.yml 注释）。 */
    static final String API_PREFIX = "/api/data-platform/v1";

    private final AuthValidationClient authValidationClient;
    private final ProblemResponses problemResponses;

    public SessionAuthFilter(AuthValidationClient authValidationClient, ProblemResponses problemResponses) {
        this.authValidationClient = authValidationClient;
        this.problemResponses = problemResponses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 非本前缀的流量不碰，避免影响无关链路（Dubbo 健康检查等）
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (token == null) {
            problemResponses.writeTo(response, problemResponses.build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "缺少登录态凭证（Authorization: Bearer <token> 或查询参数 token）"));
            return;
        }
        AuthValidationClient.AuthValidationResult auth = authValidationClient.validate(token);
        if (!auth.isValid()) {
            problemResponses.writeTo(response, problemResponses.build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "登录态校验未通过: " + (auth.getMessage() == null ? "unknown" : auth.getMessage())));
            return;
        }
        request.setAttribute(AuthContext.USER_ID_ATTRIBUTE, auth.getUserId());
        // 存原始 token：下层 proto 契约的 session_token 字段要原样回传做服务端校验，不能凭 userId 反推
        request.setAttribute(AuthContext.SESSION_TOKEN_ATTRIBUTE, token);
        filterChain.doFilter(request, response);
    }

    /** 先从 Bearer 头取；头缺失或空再退到查询参数。两种都没有返回 null，由上层回 401。 */
    private static String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring("Bearer ".length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        String queryToken = request.getParameter("token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken.trim();
        }
        return null;
    }
}
