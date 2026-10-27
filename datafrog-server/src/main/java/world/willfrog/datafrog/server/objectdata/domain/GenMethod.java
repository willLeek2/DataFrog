package world.willfrog.datafrog.server.objectdata.domain;

/** 生成方式三值（规范篇第 14 节拟定词表，随 014-data-01 转正）：fetch、indicator、agent。 */
public enum GenMethod {
    FETCH("fetch"),
    INDICATOR("indicator"),
    AGENT("agent");

    private final String value;

    GenMethod(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static GenMethod fromValue(String v) {
        for (GenMethod t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown gen_method: " + v);
    }
}
