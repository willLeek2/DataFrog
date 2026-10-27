package world.willfrog.datafrog.server.semanticindex;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Qdrant gRPC 客户端的懒构建与热重建。
 *
 * <p>懒构建：构造客户端本身不发起网络连接，放到首次使用时再建——应用启动时
 * Qdrant 可能不在线，不许因此拖垮启动。热重建：SemanticIndexSettings 的
 * qdrantConfigVersion 变化（热配置改了地址或端口）时关掉旧客户端按新地址重建。</p>
 *
 * <p>拿到客户端后顺带确保集合存在（没有就按当前维度与 Cosine 距离建，Cosine 即
 * 余弦距离，文本向量检索的常用口径，与主仓库 externalInfoService 的集合一致）；
 * 确保失败只记 error 日志不抛出——真正的写入调用会在调用点失败并记日志，
 * 集合在 Qdrant 恢复后的下一次调用再补建。</p>
 */
@Component
public class QdrantClientProvider {

    private static final Logger log = LoggerFactory.getLogger(QdrantClientProvider.class);

    /** 单次调用的最长等待；Qdrant 不可达时 gRPC 自身的重连退避可能比预期久，统一卡 10 秒。 */
    static final long RPC_TIMEOUT_SECONDS = 10;

    private final SemanticIndexSettings settings;
    private QdrantClient client;
    private int builtVersion = -1;
    /** 已完成集合确保的标识（客户端版本 + 集合名 + 维度），三者任一变化都要重新确保。 */
    private String ensuredKey = "";

    public QdrantClientProvider(SemanticIndexSettings settings) {
        this.settings = settings;
    }

    public synchronized QdrantClient get() {
        int version = settings.getQdrantConfigVersion();
        if (client == null || version != builtVersion) {
            if (client != null) {
                client.close();
            }
            // 第三参 false = 不用 TLS；当前共享 Qdrant 部署无 API key（与主仓库同实例同口径）
            client = new QdrantClient(QdrantGrpcClient
                    .newBuilder(settings.getQdrantHost(), settings.getQdrantPort(), false)
                    .build());
            builtVersion = version;
            ensuredKey = "";
            log.info("[QdrantClientProvider] 已按 {}:{} 构建 Qdrant 客户端",
                    settings.getQdrantHost(), settings.getQdrantPort());
        }
        String collection = settings.getCollection();
        String key = builtVersion + ":" + collection + ":" + settings.getEmbeddingDim();
        if (!key.equals(ensuredKey)) {
            if (ensureCollection(collection, settings.getEmbeddingDim())) {
                ensuredKey = key;
            }
        }
        return client;
    }

    private boolean ensureCollection(String collection, int dim) {
        try {
            boolean exists = client.collectionExistsAsync(collection)
                    .get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exists) {
                client.createCollectionAsync(collection, VectorParams.newBuilder()
                                .setDistance(Distance.Cosine)
                                .setSize(dim)
                                .build())
                        .get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("[QdrantClientProvider] 已创建集合 {}（dim={}, Cosine）", collection, dim);
            }
            return true;
        } catch (Exception e) {
            log.error("[QdrantClientProvider] 确保集合 {} 存在失败（不拦启动与调用，下次调用重试）: {}",
                    collection, e.getMessage());
            return false;
        }
    }

    @PreDestroy
    public synchronized void destroy() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
