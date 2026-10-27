package world.willfrog.datafrog.server.semanticindex;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 语义索引的运行时设置（Qdrant 地址与集合 + embedding 端点四项）。
 *
 * <p>启动值来自 application.yml 的 datafrog.semanticindex.*（环境变量注入）；
 * 热配置链路（server.config 包的 DatafrogRuntimeConfigLoader）在运行时经
 * overrideQdrant / overrideEmbedding 改值：null 键不动、非 null 键替换——
 * 「热配置优先、环境变量在热配置缺键时回退」就是靠这个口径实现的。</p>
 *
 * <p>Qdrant 地址或端口变化时 qdrantConfigVersion 自增，QdrantClientProvider
 * 按版本号判断要不要重建 gRPC 客户端；集合名与 embedding 参数不需要重建，
 * 调用时现读。</p>
 */
@Component
public class SemanticIndexSettings {

    private String qdrantHost;
    private int qdrantPort;
    private String collection;
    private String embeddingBaseUrl;
    private String embeddingApiKey;
    private String embeddingModel;
    private int embeddingDim;
    private int qdrantConfigVersion;

    public SemanticIndexSettings(
            @Value("${datafrog.semanticindex.qdrant-host:shared-qdrant}") String qdrantHost,
            @Value("${datafrog.semanticindex.qdrant-port:6334}") int qdrantPort,
            @Value("${datafrog.semanticindex.collection:datafrog_objects}") String collection,
            @Value("${datafrog.semanticindex.embedding-base-url:https://api.openai.com/v1}") String embeddingBaseUrl,
            @Value("${datafrog.semanticindex.embedding-api-key:}") String embeddingApiKey,
            @Value("${datafrog.semanticindex.embedding-model:text-embedding-3-small}") String embeddingModel,
            @Value("${datafrog.semanticindex.embedding-dim:1024}") int embeddingDim) {
        this.qdrantHost = qdrantHost;
        this.qdrantPort = qdrantPort;
        this.collection = collection;
        this.embeddingBaseUrl = embeddingBaseUrl;
        this.embeddingApiKey = embeddingApiKey;
        this.embeddingModel = embeddingModel;
        this.embeddingDim = embeddingDim;
        this.qdrantConfigVersion = 0;
    }

    public synchronized String getQdrantHost() {
        return qdrantHost;
    }

    public synchronized int getQdrantPort() {
        return qdrantPort;
    }

    public synchronized String getCollection() {
        return collection;
    }

    public synchronized String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public synchronized String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public synchronized String getEmbeddingModel() {
        return embeddingModel;
    }

    public synchronized int getEmbeddingDim() {
        return embeddingDim;
    }

    public synchronized int getQdrantConfigVersion() {
        return qdrantConfigVersion;
    }

    /**
     * 热配置写入 Qdrant 三键：null 键不动、非 null 键替换；
     * 地址或端口变了升版本号，QdrantClientProvider 下次取客户端时重建。
     */
    public synchronized void overrideQdrant(String host, Integer port, String newCollection) {
        boolean addressChanged = false;
        if (host != null && !host.equals(this.qdrantHost)) {
            this.qdrantHost = host;
            addressChanged = true;
        }
        if (port != null && port != this.qdrantPort) {
            this.qdrantPort = port;
            addressChanged = true;
        }
        if (newCollection != null) {
            this.collection = newCollection;
        }
        if (addressChanged) {
            this.qdrantConfigVersion++;
        }
    }

    /** 热配置写入 embedding 四键：null 键不动；这些参数调用时现读，无需版本号。 */
    public synchronized void overrideEmbedding(String baseUrl, String apiKey, String model, Integer dim) {
        if (baseUrl != null) {
            this.embeddingBaseUrl = baseUrl;
        }
        if (apiKey != null) {
            this.embeddingApiKey = apiKey;
        }
        if (model != null) {
            this.embeddingModel = model;
        }
        if (dim != null) {
            this.embeddingDim = dim;
        }
    }
}
