package world.willfrog.datafrog.server.semanticindex;

import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.PointStruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * SemanticIndexService 的 Qdrant 实现（014-data-06 方案 D6）。
 *
 * <p>点 id 的取法：平台对象 id 形如 ds_+8 位 hex，不是 Qdrant 接受的整数或 UUID 形态，
 * 用 UUID.nameUUIDFromBytes 从对象 id 派生确定性 UUID——同一对象重复 upsert 覆盖同一点，
 * 天然幂等；删除也按同一个派生 id。</p>
 *
 * <p>异常口径：embedding 或 Qdrant 调用的失败原样包成 IllegalStateException 抛出，
 * 由调用方决定后果（对象登记/升版钩子是捕获后记日志、不推翻元数据的一方）。</p>
 */
@Service
public class QdrantSemanticIndexService implements SemanticIndexService {

    private static final Logger log = LoggerFactory.getLogger(QdrantSemanticIndexService.class);

    private final QdrantClientProvider clientProvider;
    private final EmbeddingClient embeddingClient;
    private final SemanticIndexSettings settings;

    public QdrantSemanticIndexService(QdrantClientProvider clientProvider,
                                      EmbeddingClient embeddingClient,
                                      SemanticIndexSettings settings) {
        this.clientProvider = clientProvider;
        this.embeddingClient = embeddingClient;
        this.settings = settings;
    }

    @Override
    public void upsert(String objectId, String name, String description, String owner, String visibility) {
        String text = name + "\n" + (description == null ? "" : description);
        List<Float> vector = embeddingClient.embed(text);
        PointStruct point = PointStruct.newBuilder()
                .setId(id(pointUuid(objectId)))
                .setVectors(vectors(vector))
                .putAllPayload(Map.of(
                        "object_id", value(objectId),
                        "name", value(name),
                        "owner", value(owner),
                        "visibility", value(visibility)))
                .build();
        try {
            clientProvider.get().upsertAsync(settings.getCollection(), List.of(point))
                    .get(QdrantClientProvider.RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("qdrant upsert interrupted, objectId=" + objectId, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("qdrant upsert failed, objectId=" + objectId, e);
        }
        log.info("[QdrantSemanticIndexService] 已写入向量 objectId={} collection={}",
                objectId, settings.getCollection());
    }

    @Override
    public void deleteByObjectId(String objectId) {
        try {
            clientProvider.get().deleteAsync(settings.getCollection(), List.of(id(pointUuid(objectId))))
                    .get(QdrantClientProvider.RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("qdrant delete interrupted, objectId=" + objectId, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("qdrant delete failed, objectId=" + objectId, e);
        }
        log.info("[QdrantSemanticIndexService] 已删除向量 objectId={}", objectId);
    }

    @Override
    public List<SemanticHit> search(String queryText, int limit) {
        List<Float> vector = embeddingClient.embed(queryText);
        List<ScoredPoint> points;
        try {
            points = clientProvider.get().searchAsync(SearchPoints.newBuilder()
                            .setCollectionName(settings.getCollection())
                            .addAllVector(vector)
                            .setLimit(limit)
                            .build())
                    .get(QdrantClientProvider.RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("qdrant search interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("qdrant search failed", e);
        }
        List<SemanticHit> hits = new ArrayList<>(points.size());
        for (ScoredPoint point : points) {
            String objectId = point.getPayloadMap().get("object_id").getStringValue();
            hits.add(new SemanticHit(objectId, point.getScore()));
        }
        return hits;
    }

    private static UUID pointUuid(String objectId) {
        return UUID.nameUUIDFromBytes(objectId.getBytes(StandardCharsets.UTF_8));
    }
}
