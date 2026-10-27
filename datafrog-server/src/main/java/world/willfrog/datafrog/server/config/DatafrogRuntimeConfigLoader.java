package world.willfrog.datafrog.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import world.willfrog.datafrog.server.semanticindex.SemanticIndexSettings;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 运行时热配置加载器（014-data-06 方案 D8）。
 *
 * <p>读取 {@code datafrog.runtime.config-file} 指向的 JSON 文件（NacosConfigBridge
 * 把 Nacos 订阅 datafrog-runtime.json 写盘到这个路径），解析成 {@link DatafrogRuntimeConfig}
 * 后把语义索引相关的键推进 SemanticIndexSettings，登录态直通键经
 * {@link #getHotAuthPassthroughUserId()} 供 AuthValidationClient 取值。
 * 桥写盘成功会发事件立刻重读；每 10 秒轮询文件修改时间补上事件丢失的缺口。</p>
 *
 * <p>读不到文件或文件解析失败时保持现状：已应用的覆盖不清空（与 alphafrog 同款加载器
 * 同口径，桥侧整链为空时也会保留文件并打 error 日志），各消费方继续用最后一次生效值；
 * 从未读到过文件则一切回退环境变量默认。</p>
 *
 * <p>alphafrog 的同款加载器还会把加载状态经 Redis 上报（ConfigLoadStateReporter）；
 * DataFrog 尚未接 Redis，该上报未一并复制。</p>
 */
@Component
public class DatafrogRuntimeConfigLoader implements ApplicationListener<NacosLocalConfigWrittenEvent> {

    private static final Logger log = LoggerFactory.getLogger(DatafrogRuntimeConfigLoader.class);

    @Value("${datafrog.runtime.config-file:}")
    private String configFile;

    @Value("${AF_LANE_TRAFFIC_SCOPE_ID:}")
    private String laneTrafficScopeId;

    private final ObjectMapper objectMapper;
    private final SemanticIndexSettings semanticIndexSettings;
    private volatile DatafrogRuntimeConfig current;
    private final Object reloadLock = new Object();
    private long loadedConfigLastModified = -1;
    /**
     * Nacos 缓存文件是 camelCase。解析整份文件时用这份 mapper，不跟应用里可能被改成
     * 其它命名策略的 ObjectMapper 走同一套命名。
     */
    private volatile ObjectMapper fileMapper;

    public DatafrogRuntimeConfigLoader(ObjectMapper objectMapper, SemanticIndexSettings semanticIndexSettings) {
        this.objectMapper = objectMapper;
        this.semanticIndexSettings = semanticIndexSettings;
    }

    @PostConstruct
    public void load() {
        reloadIfNeeded(true);
    }

    @Scheduled(fixedDelayString = "${datafrog.runtime.config-refresh-interval-ms:10000}")
    public void refresh() {
        reloadIfNeeded(false);
    }

    @Override
    public void onApplicationEvent(NacosLocalConfigWrittenEvent event) {
        applyNacosWrittenFile(event.getTargetFile());
    }

    /**
     * Nacos 已经把生效内容写进 {@code targetFile}。路径对得上才重读，避免泳道吃到主环境那份未加前缀的缓存。
     */
    public void applyNacosWrittenFile(String targetFile) {
        if (targetFile == null || targetFile.isBlank()) {
            return;
        }
        String file = resolvedConfigFile();
        if (file.isEmpty()) {
            return;
        }
        Path configured = Paths.get(file).toAbsolutePath().normalize();
        Path written = Paths.get(targetFile).toAbsolutePath().normalize();
        if (!configured.equals(written)) {
            return;
        }
        reloadIfNeeded(true);
    }

    /** 当前已加载的热配置，从未读到文件时返回 empty。 */
    public Optional<DatafrogRuntimeConfig> current() {
        return Optional.ofNullable(current);
    }

    /**
     * 登录态直通用户标识的热值：热配置里写了 authPassthroughUserId 键（含空串）回该值，
     * 键不存在或从未读到文件回 null——null 表示「没有热值」，调用方回退环境变量。
     */
    public String getHotAuthPassthroughUserId() {
        DatafrogRuntimeConfig cfg = current;
        return cfg == null ? null : cfg.getAuthPassthroughUserId();
    }

    private String resolvedConfigFile() {
        return NacosLocalCachePaths.isolate(configFile == null ? "" : configFile.trim(), laneTrafficScopeId);
    }

    private void reloadIfNeeded(boolean force) {
        String file = resolvedConfigFile();
        if (file == null || file.isBlank()) {
            if (force) {
                log.info("[DatafrogRuntimeConfigLoader] config-file 未配置，跳过运行时热配置加载");
            }
            return;
        }
        Path path = Paths.get(file).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            if (force) {
                log.info("[DatafrogRuntimeConfigLoader] 配置文件不存在，跳过: {}", path);
            }
            return;
        }
        try {
            long currentModified = Files.getLastModifiedTime(path).toMillis();
            if (!force && currentModified == loadedConfigLastModified) {
                return;
            }
            synchronized (reloadLock) {
                if (!force && currentModified == loadedConfigLastModified) {
                    return;
                }
                try (InputStream in = Files.newInputStream(path)) {
                    byte[] bytes = in.readAllBytes();
                    DatafrogRuntimeConfig parsed = fileMapper().readValue(bytes, DatafrogRuntimeConfig.class);
                    PlaceholderResolver.resolve(parsed);
                    this.current = parsed;
                    this.loadedConfigLastModified = currentModified;
                    semanticIndexSettings.overrideQdrant(
                            parsed.getQdrantHost(), parsed.getQdrantPort(), parsed.getQdrantCollection());
                    semanticIndexSettings.overrideEmbedding(
                            parsed.getEmbeddingBaseUrl(), parsed.getEmbeddingApiKey(),
                            parsed.getEmbeddingModel(), parsed.getEmbeddingDim());
                    log.info("[DatafrogRuntimeConfigLoader] 已加载运行时热配置: {}", path);
                }
            }
        } catch (Exception e) {
            log.error("[DatafrogRuntimeConfigLoader] 加载失败: {} - {}", file, e.getMessage());
        }
    }

    private ObjectMapper fileMapper() {
        ObjectMapper cached = fileMapper;
        if (cached != null) {
            return cached;
        }
        synchronized (reloadLock) {
            if (fileMapper == null) {
                ObjectMapper copy = objectMapper.copy();
                copy.setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
                fileMapper = copy;
            }
            return fileMapper;
        }
    }
}
