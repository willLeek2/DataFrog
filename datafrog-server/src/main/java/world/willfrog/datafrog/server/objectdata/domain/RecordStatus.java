package world.willfrog.datafrog.server.objectdata.domain;

/** 版本行状态（规范篇第 14 节拟定词表）：ACTIVE 为当前版本；SUPERSEDED 为升版后封存的旧版本，
 *  旧版本字段组与 part 集合从此定格、可回滚。与缺失三态无关。 */
public enum RecordStatus {
    ACTIVE("ACTIVE"),
    SUPERSEDED("SUPERSEDED");

    private final String value;

    RecordStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
