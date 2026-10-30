package world.willfrog.datafrog.server.config;

/**
 * 运行时热配置 JSON 的映射对象（对应 NacosConfigBridge 写盘的 datafrog-runtime.local.json）。
 *
 * <p>所有键都可缺省：JSON 里不出现该键（映射后为 null）= 该项回退环境变量默认值。
 * 字符串键写空串是有效热值——例如 authPassthroughUserId 空串表示「一切登录态判无效」，
 * 与「键没写、回退环境变量」是两回事，消费方按键是否存在判断，不按值是否空判断。</p>
 *
 * <p>字符串值支持 ${ENV_NAME} 占位符（由 {@link PlaceholderResolver} 解析），
 * 密钥类值可以只放环境变量名。</p>
 */
public class DatafrogRuntimeConfig {

    /** 登录态直通用户标识（014-data-02 过渡配置的 hot 版）。 */
    private String authPassthroughUserId;

    /** Qdrant 地址（gRPC 主机名）。 */
    private String qdrantHost;

    /** Qdrant gRPC 端口。 */
    private Integer qdrantPort;

    /** 语义索引集合名。 */
    private String qdrantCollection;

    /** embedding 端点 baseUrl（OpenAI 兼容）。 */
    private String embeddingBaseUrl;

    /** embedding 端点密钥。 */
    private String embeddingApiKey;

    /** embedding 模型名。 */
    private String embeddingModel;

    /** embedding 向量维度，须与集合维度一致。 */
    private Integer embeddingDim;

    /** 导出触发限额：每用户每日次数上限（014-data-05 方案 D8，热配置起始值，默认 50）。 */
    private Integer exportDailyLimitPerUser;

    /** 导出触发限额：同一对象重复导出的最小间隔秒数（014-data-05 方案 D8，起始值，默认 60）。 */
    private Integer exportReexportCooldownSeconds;

    /** 清理执行体的周期间隔毫秒（014-data-05 方案 D6，默认六小时）。 */
    private Long maintenanceSweepIntervalMs;

    /** 维护端点的管理员用户 id 清单（逗号分隔；014-data-05 方案 D10，默认空=全部拒绝）。 */
    private String maintenanceAdminUserIds;

    /**
     * 调用 Agent 运行体系时携带的 dubbo.tag（=agent 侧泳道名，如 lane-014data05m）。
     * 泳道直连场景专用：run 行的 lane_tag 取自请求的 dubbo.tag 附件，直连不经过注册中心
     * 路由就不会自动携带，漏带会使 agent 侧租约续期的 scope 匹配（lane_tag 一项）永不命中，
     * 运行在所有权丢失后悬死至规格超时（05 phase 2 诊断轮实证）。主环境走 Nacos 路由、
     * 过滤器自动带 tag，不配置本键（空=不设附件=现状行为，向后兼容）。
     * 注意值是 agent 泳道名，不是 DataFrog 自身的泳道名（两者不同名）。
     */
    private String agentLaneTag;

    public String getAuthPassthroughUserId() {
        return authPassthroughUserId;
    }

    public void setAuthPassthroughUserId(String authPassthroughUserId) {
        this.authPassthroughUserId = authPassthroughUserId;
    }

    public String getQdrantHost() {
        return qdrantHost;
    }

    public void setQdrantHost(String qdrantHost) {
        this.qdrantHost = qdrantHost;
    }

    public Integer getQdrantPort() {
        return qdrantPort;
    }

    public void setQdrantPort(Integer qdrantPort) {
        this.qdrantPort = qdrantPort;
    }

    public String getQdrantCollection() {
        return qdrantCollection;
    }

    public void setQdrantCollection(String qdrantCollection) {
        this.qdrantCollection = qdrantCollection;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public void setEmbeddingApiKey(String embeddingApiKey) {
        this.embeddingApiKey = embeddingApiKey;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public Integer getEmbeddingDim() {
        return embeddingDim;
    }

    public void setEmbeddingDim(Integer embeddingDim) {
        this.embeddingDim = embeddingDim;
    }

    public Integer getExportDailyLimitPerUser() {
        return exportDailyLimitPerUser;
    }

    public void setExportDailyLimitPerUser(Integer exportDailyLimitPerUser) {
        this.exportDailyLimitPerUser = exportDailyLimitPerUser;
    }

    public Integer getExportReexportCooldownSeconds() {
        return exportReexportCooldownSeconds;
    }

    public void setExportReexportCooldownSeconds(Integer exportReexportCooldownSeconds) {
        this.exportReexportCooldownSeconds = exportReexportCooldownSeconds;
    }

    public Long getMaintenanceSweepIntervalMs() {
        return maintenanceSweepIntervalMs;
    }

    public void setMaintenanceSweepIntervalMs(Long maintenanceSweepIntervalMs) {
        this.maintenanceSweepIntervalMs = maintenanceSweepIntervalMs;
    }

    public String getMaintenanceAdminUserIds() {
        return maintenanceAdminUserIds;
    }

    public void setMaintenanceAdminUserIds(String maintenanceAdminUserIds) {
        this.maintenanceAdminUserIds = maintenanceAdminUserIds;
    }

    public String getAgentLaneTag() {
        return agentLaneTag;
    }

    public void setAgentLaneTag(String agentLaneTag) {
        this.agentLaneTag = agentLaneTag;
    }
}
