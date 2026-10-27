package world.willfrog.datafrog.server.client;

import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import world.willfrog.alphafrogmicro.agent.idl.AgentArtifactMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentDubboService;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunMessage;
import world.willfrog.alphafrogmicro.agent.idl.AgentRunStatusMessage;
import world.willfrog.alphafrogmicro.agent.idl.CancelAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.CreateAgentRunRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactRequest;
import world.willfrog.alphafrogmicro.agent.idl.DownloadAgentArtifactResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsRequest;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentCreditsResponse;
import world.willfrog.alphafrogmicro.agent.idl.GetAgentRunStatusRequest;

import java.util.List;

/**
 * 主仓库 Agent 运行体系的 Dubbo 客户端（014-data-02）。
 * 平台的生成任务执行体完全复用主仓库 Agent 运行：经 group=langchain 的 AgentDubboService
 * 调 createRun 拉起受控运行，经 getStatus 读状态与已消耗积分、getCredits 读余额，
 * 超时置终态时经 cancelRun 尽力取消运行。
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
     * stageConfigJson 非空时写入 createRun 请求（主仓库契约字段 11，注释写明「各阶段 LLM 配置」），
     * 服务端合并规则是逐字段以运行自带值优先于本地默认，用于按任务指定便宜模型
     * （frog 2026-10-26：测试尽量用便宜模型）。
     */
    public AgentRunMessage createRun(String userId, String message, String contextJson, String idempotencyKey,
                                     String stageConfigJson) {
        CreateAgentRunRequest.Builder builder = CreateAgentRunRequest.newBuilder()
                .setUserId(userId)
                .setMessage(message)
                .setContextJson(contextJson)
                .setIdempotencyKey(idempotencyKey);
        if (stageConfigJson != null && !stageConfigJson.isBlank()) {
            builder.setStageConfigJson(stageConfigJson);
        }
        return agentDubboService.createRun(builder.build());
    }

    public AgentRunStatusMessage getRunStatus(String userId, String runId) {
        GetAgentRunStatusRequest request = GetAgentRunStatusRequest.newBuilder()
                .setUserId(userId)
                .setId(runId)
                .build();
        return agentDubboService.getStatus(request);
    }

    /**
     * 尽力取消一次运行（014-data-02 超时置终态时调用，wang CR 必改第 4 条）。
     * 只发取消请求；运行已终态或调用失败都由调用方记日志处理，本类不抛特殊语义。
     * 结算的积分权威源是 getStatus 返回的 total_credits_consumed（与主仓库结算管线同源），
     * 不用 getRunCost——它返回的是货币金额不是积分。
     */
    public AgentRunMessage cancelRun(String userId, String runId) {
        CancelAgentRunRequest request = CancelAgentRunRequest.newBuilder()
                .setUserId(userId)
                .setId(runId)
                .build();
        return agentDubboService.cancelRun(request);
    }

    /** 用户剩余积分。主仓库目前只有查询接口，扣减没有远程入口（跨仓库依赖第 2 项，等 frog 定夺）。 */
    public int getRemainingCredits(String userId) {
        GetAgentCreditsResponse response = agentDubboService.getCredits(
                GetAgentCreditsRequest.newBuilder().setUserId(userId).build());
        return response.getRemainingCredits();
    }

    /**
     * 列出一次运行的产物附件清单（014-data-05 登记段取回运行产出的 CSV 用）。
     * 06 交付的 Agent 运行结果读取面：产物文件经 ListArtifacts + DownloadArtifact 取回，
     * 而不是塞在 GetResult 的 answer 文本里（文本通道不保证完整，文件通道才是契约产物）。
     */
    public List<AgentArtifactMessage> listRunArtifacts(String userId, String runId) {
        ListAgentArtifactsRequest request = ListAgentArtifactsRequest.newBuilder()
                .setUserId(userId)
                .setId(runId)
                .build();
        return agentDubboService.listArtifacts(request).getItemsList();
    }

    /** 下载一份产物附件的完整内容。 */
    public byte[] downloadArtifact(String userId, String artifactId) {
        DownloadAgentArtifactResponse response = agentDubboService.downloadArtifact(
                DownloadAgentArtifactRequest.newBuilder()
                        .setUserId(userId)
                        .setArtifactId(artifactId)
                        .build());
        return response.getContent().toByteArray();
    }
}
