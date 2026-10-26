package world.willfrog.datafrog.server.objectdata.domain;

/** 更新频率（规范篇第 14 节拟定词表）：daily、single_period（一次性单期）。 */
public enum Frequency {
    DAILY("daily"),
    SINGLE_PERIOD("single_period");

    private final String value;

    Frequency(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Frequency fromValue(String v) {
        for (Frequency t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown frequency: " + v);
    }
}
