package world.willfrog.datafrog.server.client;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunCostMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunCostRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;

/**
 * 主仓库 Agent 运行体系的 Dubbo 客户端（014-data-02）。
 * 平台的生成任务执行体完全复用主仓库 Agent 运行：经 group=langchain 的 AgentDubboService
 * 调 createRun 拉起受控运行，经 getStatus / getRunCost / getCredits 读取状态、费用与余额。
 * 注意：createRun 服务端不校验调用方身份，user_id 由本类调用方（受理服务）在第一跳
 * 完成登录态校验后代填，防伪造责任在平台这一侧。
 */
@Component
public class AgentRunClient {

    @DubboReference(group = "langchain", check = false)
    private AgentDubboService agentDubboService;

    /**
     * 拉起一次受控 Agent 运行。任务规格三要素（行为边界、输出格式、时限）经 contextJson 注入；
     * idempotencyKey 填任务编号，防止本类调用方重试造成重复运行。
     */
    public AgentRunMessage createRun(String userId, String message, String contextJson, String idempotencyKey) {
        CreateAgentRunRequest request = CreateAgentRunRequest.newBuilder()
                .setUserId(userId)
                .setMessage(message)
                .setContextJson(contextJson)
                .setIdempotencyKey(idempotencyKey)
                .build();
        return agentDubboService.createRun(request);
    }

    public AgentRunStatusMessage getRunStatus(String userId, String runId) {
        GetAgentRunStatusRequest request = GetAgentRunStatusRequest.newBuilder()
                .setUserId(userId)
                .setId(runId)
                .build();
        return agentDubboService.getStatus(request);
    }

    public AgentRunCostMessage getRunCost(String userId, String runId) {
        GetAgentRunCostRequest request = GetAgentRunCostRequest.newBuilder()
                .setUserId(userId)
                .setId(runId)
                .build();
        return agentDubboService.getRunCost(request);
    }

    /** 用户剩余积分。主仓库目前只有查询接口，扣减没有远程入口（跨仓库依赖第 2 项，等 frog 定夺）。 */
    public int getRemainingCredits(String userId) {
        GetAgentCreditsResponse response = agentDubboService.getCredits(
                GetAgentCreditsRequest.newBuilder().setUserId(userId).build());
        return response.getRemainingCredits();
    }
}
