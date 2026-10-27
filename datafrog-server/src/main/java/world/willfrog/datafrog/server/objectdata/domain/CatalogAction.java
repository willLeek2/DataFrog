package world.willfrog.datafrog.server.objectdata.domain;

/** 目录台账动作（规范篇第 3 节【已定】：生成、修改、删除都走目录记录；追加单列一档便于泳道分别断言）。 */
public enum CatalogAction {
    REGISTERED,
    APPENDED,
    MODIFIED,
    DELETED
}
