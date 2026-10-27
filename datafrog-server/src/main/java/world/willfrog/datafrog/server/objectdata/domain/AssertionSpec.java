package world.willfrog.datafrog.server.objectdata.domain;

import java.util.List;

/**
 * 可用性断言声明（014-data-04）：登记时随对象写入 assertions 列（JSONB），结果不落库、查询时现算。
 * v1 三类：coverage_end_at_or_after（参数 date_value）/ row_count_at_least（参数 count_value）/
 * depends_on_objects_pass（参数 depends_on，依赖对象 id 数组）。
 * type 用字符串而不是枚举：将来扩充新类型时旧数据可原样存读；认不出的类型在求值时判不通过
 * （既定语义，登记校验对类型字符串不做白名单）。
 */
public record AssertionSpec(String type, String dateValue, long countValue, List<String> dependsOn) {
}
