package world.willfrog.datafrog.server.objectdata.service;

import org.springframework.stereotype.Service;
import world.willfrog.datafrog.server.objectdata.domain.AssertionSpec;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
        validateAssertions(cmd.assertions());
    }

    /**
     * 断言登记校验（014-data-04，从轻）：type 必填；三类已知类型各查自己的参数；
     * 类型字符串不做白名单——认不出的类型在求值时判不通过（既定语义），登记不拦。
     */
    public void validateAssertions(List<AssertionSpec> assertions) {
        if (assertions == null) {
            return;
        }
        for (AssertionSpec a : assertions) {
            if (a.type() == null || a.type().isBlank()) {
                throw new ObjectStoreExceptions.InvalidArgumentException("断言 type 不能为空");
            }
            switch (a.type()) {
                case "coverage_end_at_or_after" -> {
                    if (!isParsableDate(a.dateValue())) {
                        throw new ObjectStoreExceptions.InvalidArgumentException(
                                "coverage_end_at_or_after 的 date_value 须可解析为 YYYY-MM-DD: " + a.dateValue());
                    }
                }
                case "row_count_at_least" -> {
                    if (a.countValue() < 0) {
                        throw new ObjectStoreExceptions.InvalidArgumentException(
                                "row_count_at_least 的 count_value 须 >= 0: " + a.countValue());
                    }
                }
                case "depends_on_objects_pass" -> {
                    if (a.dependsOn() == null || a.dependsOn().isEmpty()) {
                        throw new ObjectStoreExceptions.InvalidArgumentException(
                                "depends_on_objects_pass 的 depends_on 不能为空");
                    }
                }
                default -> {
                    // 认不出的类型不在这里拒（求值时按 unknown_type 判不通过）
                }
            }
        }
    }

    private static boolean isParsableDate(String v) {
        if (v == null) {
            return false;
        }
        try {
            LocalDate.parse(v);
            return true;
        } catch (DateTimeParseException e) {
            return false;
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
