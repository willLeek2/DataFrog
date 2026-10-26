package world.willfrog.datafrog.server.client;

/**
 * 积分读写契约（014-data-02）。
 *
 * 背景与 AuthValidationClient 相同：主仓库远程可做的只有查询余额（GetCredits）
 * 与提交加分申请（ApplyCredits），实际扣减只在 Agent Run 结算管线内部发生，
 * 没有对外 RPC。按「平台定义接口、主仓库实现后注册进来」的原则，本接口是平台侧
 * 契约；主仓库 provider 落地前由本地直通实现顶着（扣减与回补退化为记录意图，
 * 不真实拦截余额），切换时只换实现类，task 包逻辑不动。
 */
public interface CreditClient {

    /** 用户当前剩余积分。本地直通期间这一读是真的，直连主仓库 GetCredits。 */
    int getRemainingCredits(String userId);

    /**
     * 受理时预扣。idempotencyKey 填任务编号，保证同一任务重复调用不重复扣。
     * 本地直通期间只记录意图并返回 true。
     */
    boolean debit(String userId, int amount, String idempotencyKey);

    /**
     * 终态结算：实结 settledAmount，预扣与实结之差回补给用户。
     * 未拉起运行的任务 settledAmount 传 0，即预扣全额回补。
     * 本地直通期间只记录意图。
     */
    void settle(String userId, int chargedAmount, int settledAmount, String idempotencyKey);
}
