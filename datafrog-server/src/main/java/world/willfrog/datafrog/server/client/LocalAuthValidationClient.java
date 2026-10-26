package world.willfrog.datafrog.server.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AuthValidationClient 的本地直通实现（014-data-02 过渡形态）。
 *
 * 主仓库的鉴权校验 provider 落地前，受理链路不能停摆，也不能假装有真校验。
 * 行为规则：
 *   - 配置项 datafrog.auth.passthrough-user-id 为空（默认）时，一切凭证都判无效，
 *     受理链路在第一跳就被拦下，不会在身份未明的情况下拉起运行；
 *   - 配置为非空用户标识时，任何非空 session_token 都视为该用户通过校验，
 *     仅供泳道验收期间打通受理链路使用。
 *
 * 主仓库 provider 落地后本类被远程实现替换，task 包逻辑不动。
 */
@Component
public class LocalAuthValidationClient implements AuthValidationClient {

    private static final Logger log = LoggerFactory.getLogger(LocalAuthValidationClient.class);

    private final String passthroughUserId;

    public LocalAuthValidationClient(
            @Value("${datafrog.auth.passthrough-user-id:}") String passthroughUserId) {
        this.passthroughUserId = passthroughUserId == null ? "" : passthroughUserId.trim();
    }

    @Override
    public AuthValidationResult validate(String sessionToken) {
        if (passthroughUserId.isEmpty()) {
            return AuthValidationResult.invalid("auth validation provider not configured");
        }
        if (sessionToken == null || sessionToken.isBlank()) {
            return AuthValidationResult.invalid("session token is blank");
        }
        log.info("auth passthrough: token accepted as configured user {}", passthroughUserId);
        return AuthValidationResult.valid(passthroughUserId);
    }
}
