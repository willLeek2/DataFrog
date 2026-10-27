package world.willfrog.datafrog.server.maintenance;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 留存与清理的核验记录（014-data-05 方案 D7）：核验动作依附在清理执行体
 * （RetentionSweeper）上——每次物理清理后逐条回读核验写 PURGE 记录；每个周期对
 * 未到期对象全量核验文件在不在写 RETAIN 记录。异常显式标记=verdict=VIOLATION 的
 * 记录可独立过滤查询，人工介入路径就是查这个端点，不需要记录任何到期时间点。
 *
 * 全量核验、不抽样（原方案的抽样设计按 bob 意见删除，相应配置键一并取消）：
 * 平台初期对象不多，抽样省不了多少事、还留「漏检的对象恰好在样本外」的口子。
 */
@Component
public class RetentionCheckRecorder {

    /** 查询端点透出的核验记录视图。 */
    public record RetentionCheckView(String objectId, String checkType, String expected, String actual,
                                     String verdict, String detail, long checkedAt) {
    }

    private final RetentionCheckMapper mapper;

    public RetentionCheckRecorder(RetentionCheckMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 物理清理后的核验：期望文件与向量点都已删除。实测有一个没删干净就是 VIOLATION
     * （detail 写明缺哪样）；两个都不在了才是 OK。
     */
    public void recordPurge(String objectId, boolean fileAbsent, boolean pointAbsent, String detail) {
        String actual = (fileAbsent ? "文件已删" : "文件仍在") + "，" + (pointAbsent ? "向量点已删" : "向量点仍在");
        String verdict = fileAbsent && pointAbsent ? "OK" : "VIOLATION";
        mapper.insert(objectId, "PURGE", "文件与向量点均已删除", actual, verdict,
                verdict.equals("OK") ? "" : (detail == null ? "" : detail), System.currentTimeMillis());
    }

    /** 周期核验：期望内容文件仍在（只看存在、不读内容）。不在即 VIOLATION。 */
    public void recordRetain(String objectId, boolean filePresent, String detail) {
        mapper.insert(objectId, "RETAIN", "内容文件仍在", filePresent ? "内容文件仍在" : "内容文件缺失",
                filePresent ? "OK" : "VIOLATION",
                filePresent ? "" : (detail == null ? "" : detail), System.currentTimeMillis());
    }

    /** 核验记录查询（维护端点用）：object_id 与 verdict（OK / VIOLATION）都可选。 */
    public List<RetentionCheckView> query(String objectId, String verdict) {
        return mapper.query(objectId, verdict, 500).stream()
                .map(r -> new RetentionCheckView(r.objectId(), r.checkType(), r.expected(), r.actual(),
                        r.verdict(), r.detail(), r.checkedAt()))
                .toList();
    }
}
