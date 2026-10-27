package world.willfrog.datafrog.server.objectdata.service;

import org.springframework.stereotype.Service;
import world.willfrog.datafrog.server.objectdata.domain.SemanticRef;
import world.willfrog.datafrog.server.objectdata.repo.SemanticDefinitionMapper;
import world.willfrog.datafrog.server.objectdata.repo.SemanticDefinitionRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** 语义定义登记与查询；requireAll 是「生成与修改必须挂靠已有定义」的执行点。 */
@Service
public class SemanticDefinitionService {

    /** 词表口径：sd_ 的名称部分为英文下划线小写。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]+");

    private final SemanticDefinitionMapper mapper;

    public SemanticDefinitionService(SemanticDefinitionMapper mapper) {
        this.mapper = mapper;
    }

    public SemanticDefinitionRow register(String name, int version, String displayName, String definition,
                                          String calculation, String unit, String scope, String origin) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new ObjectStoreExceptions.InvalidArgumentException(
                    "语义定义名称须为英文下划线小写: " + name);
        }
        if (version < 1) {
            throw new ObjectStoreExceptions.InvalidArgumentException("语义定义版本须为正整数: " + version);
        }
        if (isBlank(displayName) || isBlank(definition)) {
            throw new ObjectStoreExceptions.InvalidArgumentException("语义定义的名称与定义不能为空");
        }
        if (mapper.getByNameVersion(name, version) != null) {
            throw new ObjectStoreExceptions.DuplicateDefinitionException(name, version);
        }
        long now = System.currentTimeMillis();
        mapper.insert(name, version, displayName, definition, nullToEmpty(calculation), nullToEmpty(unit),
                nullToEmpty(scope), origin == null ? "manual" : origin, now);
        return mapper.getByNameVersion(name, version);
    }

    public Optional<SemanticDefinitionRow> get(String name, int version) {
        return Optional.ofNullable(mapper.getByNameVersion(name, version));
    }

    public List<SemanticDefinitionRow> list(int limit, int offset) {
        return mapper.list(limit, offset);
    }

    public long count() {
        return mapper.count();
    }

    /** 挂靠校验：任一引用不存在即失败；失败信息列出全部缺失项，便于调用方一次改对。 */
    public void requireAll(List<SemanticRef> refs) {
        List<String> missing = new ArrayList<>();
        for (SemanticRef ref : refs) {
            if (mapper.getByNameVersion(ref.name(), ref.version()) == null) {
                missing.add(ref.toRefString());
            }
        }
        if (!missing.isEmpty()) {
            throw new ObjectStoreExceptions.SemanticRefNotFoundException(missing);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
