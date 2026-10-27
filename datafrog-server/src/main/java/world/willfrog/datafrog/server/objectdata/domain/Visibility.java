package world.willfrog.datafrog.server.objectdata.domain;

/** 可见性档位（规范篇第 14 节已定事项第 2 条）：v1.4 开放私有与所有者可见起步。 */
public enum Visibility {
    PRIVATE("private"),
    OWNER_VISIBLE("owner_visible");

    private final String value;

    Visibility(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Visibility fromValue(String v) {
        for (Visibility t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown visibility: " + v);
    }
}
