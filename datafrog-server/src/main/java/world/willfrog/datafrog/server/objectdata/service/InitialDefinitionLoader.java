package world.willfrog.datafrog.server.objectdata.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectMapper;
import world.willfrog.datafrog.server.objectdata.repo.DataObjectVersionMapper;
import world.willfrog.datafrog.server.objectdata.repo.SemanticDefinitionMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 01304 方法目录 → 初始语义定义（18 条，D8）。
 *
 * fail-closed：启动时先查四张元数据表是否都在（建表脚本 db/datafrog-objectstore-v1.sql
 * 由部署链路在进程启动前执行），缺表或任一 YAML 不完整都直接让启动失败——不导入一半。
 * 幂等：按 name+version 先查后插，已存在跳过，重复部署结果一致。
 *
 * 字段映射（01304 方法 spec → 语义定义）：
 * name=methodId 去命名空间（末段）；version=spec 版本号的 major 段；
 * displayName/definition 原样；calculation=conventions 首个子键的 canonicalPath（缺则 narrativeTemplate）；
 * unit=outputs 逐输出「字段名=单位」分号串；scope=resolverHints.aliases 顿号串；origin=migrated。
 */
@Component
public class InitialDefinitionLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialDefinitionLoader.class);

    private final SemanticDefinitionMapper definitionMapper;
    private final DataObjectMapper objectMapper;
    private final DataObjectVersionMapper versionMapper;

    public InitialDefinitionLoader(SemanticDefinitionMapper definitionMapper, DataObjectMapper objectMapper,
                                   DataObjectVersionMapper versionMapper) {
        this.definitionMapper = definitionMapper;
        this.objectMapper = objectMapper;
        this.versionMapper = versionMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        requireTables();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:semantic-definitions/initial/*.yaml");
        } catch (Exception e) {
            throw new IllegalStateException("读取初始语义定义目录失败", e);
        }
        if (resources.length == 0) {
            throw new IllegalStateException("初始语义定义为空：semantic-definitions/initial/ 下没有 YAML");
        }
        int inserted = 0;
        int skipped = 0;
        for (Resource resource : resources) {
            Map<String, Object> spec;
            try (InputStream in = resource.getInputStream()) {
                spec = new Yaml().load(in);
            } catch (Exception e) {
                throw new IllegalStateException("解析方法 spec 失败: " + resource.getFilename(), e);
            }
            Definition def = map(resource.getFilename(), spec);
            if (definitionMapper.getByNameVersion(def.name(), def.version()) != null) {
                skipped++;
                continue;
            }
            definitionMapper.insert(def.name(), def.version(), def.displayName(), def.definition(),
                    def.calculation(), def.unit(), def.scope(), "migrated", System.currentTimeMillis());
            inserted++;
        }
        log.info("初始语义定义加载完成：新增 {} 条，已存在跳过 {} 条，来源 {} 个 YAML", inserted, skipped, resources.length);
    }

    private void requireTables() {
        List<String> missing = new ArrayList<>();
        if (!objectMapper.tableExists()) {
            missing.add("datafrog_data_object");
        }
        if (!definitionMapper.tableExists()) {
            missing.add("datafrog_semantic_definition");
        }
        if (!versionMapper.tableExists()) {
            missing.add("datafrog_object_version");
        }
        if (!versionMapper.catalogTableExists()) {
            missing.add("datafrog_catalog_record");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("元数据表未就位（先执行 db/datafrog-objectstore-v1.sql）: "
                    + String.join(", ", missing));
        }
    }

    private record Definition(String name, int version, String displayName, String definition,
                              String calculation, String unit, String scope) {
    }

    @SuppressWarnings("unchecked")
    private Definition map(String fileName, Map<String, Object> spec) {
        String methodId = str(spec.get("methodId"), fileName, "methodId");
        String name = methodId.substring(methodId.lastIndexOf('.') + 1);
        int version = majorVersion(spec.get("version"));
        String displayName = str(spec.get("displayName"), fileName, "displayName");
        String definition = str(spec.get("definition"), fileName, "definition");
        String calculation = calculationOf((Map<String, Object>) spec.get("conventions"));
        String unit = unitOf((List<Object>) spec.get("outputs"));
        String scope = scopeOf((Map<String, Object>) spec.get("resolverHints"));
        return new Definition(name, version, displayName, definition, calculation, unit, scope);
    }

    private static int majorVersion(Object v) {
        String s = v == null ? "1" : String.valueOf(v);
        int dot = s.indexOf('.');
        return Integer.parseInt(dot < 0 ? s : s.substring(0, dot));
    }

    private static String str(Object v, String fileName, String field) {
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalStateException("方法 spec 缺字段 " + field + ": " + fileName);
        }
        return String.valueOf(v);
    }

    private static String calculationOf(Map<String, Object> conventions) {
        if (conventions == null) {
            return "";
        }
        for (Object value : conventions.values()) {
            if (value instanceof Map<?, ?> m) {
                Object canonical = m.get("canonicalPath");
                if (canonical != null) {
                    return String.valueOf(canonical);
                }
            }
        }
        for (Object value : conventions.values()) {
            if (value instanceof Map<?, ?> m && m.get("narrativeTemplate") != null) {
                return String.valueOf(m.get("narrativeTemplate"));
            }
        }
        return "";
    }

    private static String unitOf(List<Object> outputs) {
        if (outputs == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Object o : outputs) {
            if (o instanceof Map<?, ?> m) {
                parts.add(m.get("name") + "=" + m.get("unit"));
            }
        }
        return String.join("; ", parts);
    }

    private static String scopeOf(Map<String, Object> resolverHints) {
        if (resolverHints == null || !(resolverHints.get("aliases") instanceof List<?> aliases)) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Object a : aliases) {
            parts.add(String.valueOf(a));
        }
        return String.join("、", parts);
    }
}
