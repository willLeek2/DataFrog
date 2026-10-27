package world.willfrog.datafrog.server.objectdata.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 登记卡级约定校验（规范篇第 4-10 节【已定】条目的可机判部分）。
 * 内容级约定（资产代码后缀式编码、占位符字典取值、时区毫秒口径）作用在数据行上，
 * 由生产侧（014-data-03）在写入前保证，本模块存储侧按 VARCHAR 穿透、导出时原样带出；
 * 价格类必填复权、跨市场金额必声明币种需要内容语义才能判定，登记卡上不设机判。
 */
@Service
public class ConventionValidator {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** 登记卡校验；missingInputs 为调用方已查出未登记的输入对象 id（须已登记，规范篇登记卡口径）。 */
    public void validateRegistration(DataObjectRegistryService.RegisterCommand cmd, List<String> missingInputs) {
        requireText(cmd.name(), "name 不能为空");
        requireText(cmd.description(), "description 不能为空");
        requireText(cmd.owner(), "owner 不能为空");
        requireText(cmd.coverageScope(), "coverage_scope 不能为空");
        if (cmd.semanticRefs() == null || cmd.semanticRefs().isEmpty()) {
            throw new ObjectStoreExceptions.ConventionViolationException("semantic_refs 不能为空（生成与修改必须挂靠已有定义）");
        }
        if (cmd.primaryKey() == null || cmd.primaryKey().isEmpty()) {
            throw new ObjectStoreExceptions.ConventionViolationException("primary_key 不能为空（对外自然主键必须声明）");
        }
        if (!missingInputs.isEmpty()) {
            throw new ObjectStoreExceptions.ConventionViolationException("输入对象未登记: " + String.join(", ", missingInputs));
        }
        validateCoverage(cmd.coverageStart(), cmd.coverageEnd());
        // proto3 的 string getter 不返回 null（缺省即空串），省略参数的默认值判定必须按空串走，
        // 否则合法的「省略 export_format / compression」会被下面的取值校验误拒（泳道 run2 实测）。
        String format = cmd.exportFormat() == null || cmd.exportFormat().isEmpty() ? "csv" : cmd.exportFormat();
        String compression = cmd.compression() == null || cmd.compression().isEmpty() ? "none" : cmd.compression();
        if (!"csv".equals(format)) {
            throw new ObjectStoreExceptions.ConventionViolationException("export_format 仅支持 csv: " + format);
        }
        if (!"none".equals(compression) && !"zip".equals(compression)) {
            throw new ObjectStoreExceptions.ConventionViolationException("compression 仅支持 none、zip: " + compression);
        }
        if (cmd.csvContent() == null || cmd.csvContent().length == 0) {
            throw new ObjectStoreExceptions.InvalidArgumentException("csv_content 不能为空");
        }
    }

    /** 覆盖区间格式与先后关系（YYYY-MM-DD，start ≤ end；单期对象两端同日）。 */
    public void validateCoverage(String start, String end) {
        if (start == null || !DATE.matcher(start).matches()) {
            throw new ObjectStoreExceptions.ConventionViolationException("coverage_start 须为 YYYY-MM-DD: " + start);
        }
        if (end == null || !DATE.matcher(end).matches()) {
            throw new ObjectStoreExceptions.ConventionViolationException("coverage_end 须为 YYYY-MM-DD: " + end);
        }
        if (start.compareTo(end) > 0) {
            throw new ObjectStoreExceptions.ConventionViolationException(
                    "coverage_start 晚于 coverage_end: " + start + " > " + end);
        }
    }

    private static void requireText(String v, String message) {
        if (v == null || v.isBlank()) {
            throw new ObjectStoreExceptions.ConventionViolationException(message);
        }
    }
}
