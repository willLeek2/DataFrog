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
}
