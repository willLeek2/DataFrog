package world.willfrog.datafrog.server.objectdata.domain;

/** 币种（规范篇第 7、14 节）：跨市场数据对象的金额字段必须声明。 */
public enum Ccy {
    CNY("CNY"),
    HKD("HKD"),
    USD("USD");

    private final String value;

    Ccy(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Ccy fromValue(String v) {
        for (Ccy t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown ccy: " + v);
    }
}
