package world.willfrog.datafrog.server.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CreditClient 的本地直通实现（014-data-02 过渡形态）。
 *
 * 余额查询是真调用（经 AgentRunClient 直连主仓库 GetCredits）；扣减与回补
 * 在主仓库扣减 provider 落地前只记录意图日志，不真实变动余额。任务表上的
 * credit_charged / credit_settled 字段照常完整流转，切真客户端后语义不变。
 */
@Component
public class LocalCreditClient implements CreditClient {

    private static final Logger log = LoggerFactory.getLogger(LocalCreditClient.class);

    private final AgentRunClient agentRunClient;

    public LocalCreditClient(AgentRunClient agentRunClient) {
        this.agentRunClient = agentRunClient;
    }

    @Override
    public int getRemainingCredits(String userId) {
        return agentRunClient.getRemainingCredits(userId);
    }

    @Override
    public boolean debit(String userId, int amount, String idempotencyKey) {
        log.info("credit debit intent (passthrough, no real charge): user={} amount={} key={}",
                userId, amount, idempotencyKey);
        return true;
    }

    @Override
    public void settle(String userId, int chargedAmount, int settledAmount, String idempotencyKey) {
        log.info("credit settle intent (passthrough, no real charge): user={} charged={} settled={} refund={} key={}",
                userId, chargedAmount, settledAmount, chargedAmount - settledAmount, idempotencyKey);
    }
}
