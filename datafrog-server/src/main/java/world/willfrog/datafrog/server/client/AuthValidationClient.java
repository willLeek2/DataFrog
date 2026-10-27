package world.willfrog.datafrog.server.client;

/**
 * 登录态校验契约（014-data-02）。创建生成任务前必须先经本接口确认调用者身份。
 *
 * 背景：架构决定平台调主仓库公开校验接口验证登录态，但主仓库目前没有可远程调用的
 * 鉴权校验服务（JWT 链在 frontend 模块进程内部）。按架构篇「平台定义接口、主仓库实现
 * 后注册进来」的原则，本接口是平台侧契约；主仓库 provider 落地前由本地直通实现顶着，
 * 切换时只换实现类，不动 task 包逻辑。frog 定夺记录见 014-data-02 进度页。
 */
public interface AuthValidationClient {

    /**
     * 校验登录态凭证。
     *
     * @return 校验结果；valid 为 true 时 userId 非空
     */
    AuthValidationResult validate(String sessionToken);

    final class AuthValidationResult {
        private final boolean valid;
        private final String userId;
        private final String message;

        private AuthValidationResult(boolean valid, String userId, String message) {
            this.valid = valid;
            this.userId = userId;
            this.message = message;
        }

        public static AuthValidationResult valid(String userId) {
            return new AuthValidationResult(true, userId, null);
        }

        public static AuthValidationResult invalid(String message) {
            return new AuthValidationResult(false, null, message);
        }

        public boolean isValid() { return valid; }
        public String getUserId() { return userId; }
        public String getMessage() { return message; }
    }
}
