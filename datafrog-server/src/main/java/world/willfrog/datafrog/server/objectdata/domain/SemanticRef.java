package world.willfrog.datafrog.server.objectdata.domain;

/** 语义定义引用：sd_ 加名称加 _v 加整数（规范篇第 14 节），如 sd_price_hfq_v1。 */
public record SemanticRef(String name, int version) {

    public String toRefString() {
        return "sd_" + name + "_v" + version;
    }
}
