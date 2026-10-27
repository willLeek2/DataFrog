package world.willfrog.datafrog.server.semanticindex;

import java.util.List;

/**
 * 语义索引服务的显式接口（014-data-06 方案 D6）。
 *
 * <p>语义索引 = 把数据对象的名字与描述经 embedding（把文本算成定长向量）存进 Qdrant
 * 向量库，供「按自然语言提问匹配数据对象」的语义查询使用——查询消费面是 014-data-05，
 * 本轮先把接线与写入备好。</p>
 *
 * <p>写入方是对象存储模块（server.objectdata）：登记（REGISTERED）与升版（MODIFIED）
 * 两个时机调 upsert；追加（APPENDED）只动数据覆盖日期不动描述，不重写向量；
 * 删除后的索引清理由 05 经 deleteByObjectId 接走，01 的删除路径本轮不调本接口。</p>
 */
public interface SemanticIndexService {

    /** 语义检索命中项：对象 id 与相似度分数（Qdrant 按距离度量算分，越大越相似）。 */
    record SemanticHit(String objectId, double score) {}

    /**
     * 按对象 id 写入或覆盖向量点；同一对象重复调用覆盖同一点（幂等）。
     * payload（向量点上附带的元数据）带 object_id、name、owner、visibility 四键，
     * 供检索侧按归属与可见性过滤。
     */
    void upsert(String objectId, String name, String description, String owner, String visibility);

    /** 按对象 id 删除向量点；对象本就不在索引里时不算错误。 */
    void deleteByObjectId(String objectId);

    /** 按自然语言文本检索相似对象，按相似度从高到低最多返回 limit 条（05 的消费面）。 */
    List<SemanticHit> search(String queryText, int limit);
}
