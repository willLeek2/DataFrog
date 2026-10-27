package world.willfrog.datafrog.server.httpapi;

import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 过滤器与控制器之间的登录态上下文：属性键与读取入口集中在一处，
 * 键名带全类名前缀，避免与其他过滤器写入的属性撞名。
 */
public final class AuthContext {

    public static final String USER_ID_ATTRIBUTE = AuthContext.class.getName() + ".USER_ID";

    public static final String SESSION_TOKEN_ATTRIBUTE = AuthContext.class.getName() + ".SESSION_TOKEN";

    private AuthContext() {
    }

    /**
     * 取当前用户标识。正常链路过滤器必然写入；取不到说明请求绕过了过滤器，
     * 按未认证回 401，而不是让空值往下传引发空指针、最后变成莫名其妙的 500。
     */
    public static String requireUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(USER_ID_ATTRIBUTE);
        if (userId instanceof String s && !s.isEmpty()) {
            return s;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录态缺失");
    }

    /**
     * 取原始 session token（登录态凭证），供下层 proto 契约的 session_token 字段原样回填。
     * 02 拿它做服务端校验，所以必须传原始凭证，不能凭 userId 反推或留空。
     */
    public static String requireSessionToken(HttpServletRequest request) {
        Object token = request.getAttribute(SESSION_TOKEN_ATTRIBUTE);
        if (token instanceof String s && !s.isEmpty()) {
            return s;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录态缺失");
    }
}
