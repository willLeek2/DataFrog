package world.willfrog.datafrog.server.objectdata.domain;

/** 来源两类（规范篇第 1 节【已定】）：系统生产按通道管理，用户生成按 credit 记到人。 */
public enum SourceType {
    SYSTEM("system"),
    USER("user");

    private final String value;

    SourceType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SourceType fromValue(String v) {
        for (SourceType t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown source_type: " + v);
    }
}
