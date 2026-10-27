package world.willfrog.datafrog.server.semanticindex;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * embedding（把文本算成定长向量）调用端，走 OpenAI 兼容 HTTP 端点。
 *
 * <p>调用口径与主仓库 externalInfoService 的 EmbeddingApiClient 一致
 * （POST {baseUrl}/embeddings，Bearer 密钥，应答取 data[0].embedding）；
 * 两边用同一个模型与维度，向量才落在同一个向量空间里。</p>
 *
 * <p>配置每次调用现读 SemanticIndexSettings，热配置改值立即生效。</p>
 */
@Component
public class EmbeddingClient {

    private final RestClient restClient;
    private final SemanticIndexSettings settings;

    public EmbeddingClient(RestClient.Builder builder, SemanticIndexSettings settings) {
        this.restClient = builder.build();
        this.settings = settings;
    }

    @SuppressWarnings("unchecked")
    public List<Float> embed(String text) {
        String apiKey = settings.getEmbeddingApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            // 未配置向量化密钥是部署配置缺口，不是代码路径问题；
            // 索引写入方（对象登记/升版钩子）会捕获并按「索引写入失败」记日志
            throw new IllegalStateException(
                    "embedding api key not configured (datafrog.semanticindex.embedding-api-key)");
        }
        Map<String, Object> body = Map.of(
                "model", settings.getEmbeddingModel(),
                "input", text,
                "dimensions", settings.getEmbeddingDim());
        Map<?, ?> response = restClient.post()
                .uri(settings.getEmbeddingBaseUrl() + "/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("data") instanceof List)) {
            throw new IllegalStateException("embedding api returned invalid response: missing 'data' field");
        }
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data.isEmpty()) {
            throw new IllegalStateException("embedding api returned empty data array");
        }
        List<Double> raw = (List<Double>) data.get(0).get("embedding");
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("embedding api returned empty embedding vector");
        }
        return raw.stream().map(Double::floatValue).toList();
    }
}
