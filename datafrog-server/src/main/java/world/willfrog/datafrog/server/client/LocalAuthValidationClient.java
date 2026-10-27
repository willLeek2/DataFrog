package world.willfrog.datafrog.server.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import world.willfrog.datafrog.server.config.DatafrogRuntimeConfigLoader;

/**
 * AuthValidationClient 的本地直通实现（014-data-02 过渡形态）。
 *
 * 主仓库的鉴权校验 provider 落地前，受理链路不能停摆，也不能假装有真校验。
 * 行为规则：
 *   - 生效的直通用户标识为空（默认）时，一切凭证都判无效，
 *     受理链路在第一跳就被拦下，不会在身份未明的情况下拉起运行；
 *   - 生效值为非空用户标识时，任何非空 session_token 都视为该用户通过校验，
 *     仅供泳道验收期间打通受理链路使用。
 *
 * 生效值的取法（014-data-06 热配置接入）：热配置优先、环境变量在热配置缺键时回退。
 * 热配置写没写这个键按「键是否存在」判断（空串是有效热值，表示一切判无效），
 * 不是按值是否空判断。迁移期部署顺序：先让运维在热配置组登记键值、确认进程读到，
 * 再合入本读法改动，避免配置源空窗期全量判无效。
 *
 * 主仓库 provider 落地后本类被远程实现替换，task 包逻辑不动。
 */
@Component
public class LocalAuthValidationClient implements AuthValidationClient {

    private static final Logger log = LoggerFactory.getLogger(LocalAuthValidationClient.class);

    private final String envPassthroughUserId;
    private final DatafrogRuntimeConfigLoader runtimeConfigLoader;

    public LocalAuthValidationClient(
            @Value("${datafrog.auth.passthrough-user-id:}") String passthroughUserId,
            DatafrogRuntimeConfigLoader runtimeConfigLoader) {
        this.envPassthroughUserId = passthroughUserId == null ? "" : passthroughUserId.trim();
        this.runtimeConfigLoader = runtimeConfigLoader;
    }

    @Override
    public AuthValidationResult validate(String sessionToken) {
        String effective = effectivePassthroughUserId();
        if (effective.isEmpty()) {
            return AuthValidationResult.invalid("auth validation provider not configured");
        }
        if (sessionToken == null || sessionToken.isBlank()) {
            return AuthValidationResult.invalid("session token is blank");
        }
        log.info("auth passthrough: token accepted as configured user {}", effective);
        return AuthValidationResult.valid(effective);
    }

    /** 热配置写了该键（含空串）用热值，否则用环境变量值。 */
    private String effectivePassthroughUserId() {
        String hot = runtimeConfigLoader.getHotAuthPassthroughUserId();
        if (hot != null) {
            return hot.trim();
        }
        return envPassthroughUserId;
    }
}
