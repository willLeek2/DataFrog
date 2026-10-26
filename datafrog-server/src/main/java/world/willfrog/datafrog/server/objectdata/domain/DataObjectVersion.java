package world.willfrog.datafrog.server.objectdata.domain;

import java.util.List;

/**
 * 数据对象版本行。字段分三组：
 * 定格组（写入后不可变）：version、versionNote、prevVersion、coverageStart（登记时写入，追加不改）、
 *   contentPath（版本目录）、createdAt。
 * 水位组（唯一更新入口=DataObjectVersionMapper.advanceWatermark，三件一起动）：
 *   rowCount、coverageEnd、contentChecksum（当前全部 part 按文件名升序拼接的 sha256，随追加重算）。
 * 追加组（只增不改）：partChecksums 各 part 的 sha256；partCount 随新 part 递增。
 * 升版封存：旧行置 SUPERSEDED 后全部字段不再更新，可回滚=回指旧行与旧 part 集合。
 */
public record DataObjectVersion(
        String objectId,
        int version,
        String versionNote,
        Integer prevVersion,
        String coverageStart,
        String contentPath,
        int partCount,
        List<String> partChecksums,
        long rowCount,
        String coverageEnd,
        String contentChecksum,
        String status,
        long createdAt,
        long watermarkUpdatedAt
) {
}
