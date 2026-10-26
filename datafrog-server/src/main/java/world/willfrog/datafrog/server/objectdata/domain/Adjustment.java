package world.willfrog.datafrog.server.objectdata.domain;

/** 复权状态（规范篇第 7、14 节）：价格类数据一等属性，一个对象内不混装不同复权基准。 */
public enum Adjustment {
    RAW("raw"),
    QFQ("qfq"),
    HFQ("hfq");

    private final String value;

    Adjustment(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Adjustment fromValue(String v) {
        for (Adjustment t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown adjustment: " + v);
    }
}
