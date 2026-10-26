package world.willfrog.datafrog.server.objectdata.domain;

import java.util.List;

/**
 * 数据对象登记卡（对象行，声明与身份类字段）。
 * 水位与覆盖四项（rowCount、coverageStart、coverageEnd、汇总 checksum）不落本实体——
 * 它们唯一存在当前 ACTIVE 版本行上，读取时投影（bob 审定 D5，杜绝双源）。
 * shard_count 不落任何表：单次导出按第 10 节单文件上限现算，只在导出应答里。
 */
public record DataObject(
        String id,
        String name,
        String description,
        SourceType sourceType,
        String owner,
        Visibility visibility,
        List<SemanticRef> semanticRefs,
        String coverageScope,
        Frequency frequency,
        GenMethod genMethod,
        String genTask,
        List<String> inputs,
        List<String> primaryKey,
        Adjustment adjustment,
        Ccy ccy,
        int currentVersion,
        String exportFormat,
        String compression,
        String status,
        long createdAt,
        long updatedAt
) {
}
