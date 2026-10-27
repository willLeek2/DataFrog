package world.willfrog.datafrog.server.objectdata.domain;

/**
 * 可用性断言汇总（014-data-04，现算不落库）：未登记断言=NO_ASSERTIONS；登记了断言的，
 * 各条独立判定后全过=PASS、部分通过=PARTIAL、全不过=FAIL。
 * 取值与契约枚举 AssertionStatus 同名，rpc 层按名换算（proto 侧另有 *_UNKNOWN 表示不加过滤条件）。
 */
public enum AssertionStatus {
    NO_ASSERTIONS,
    PASS,
    PARTIAL,
    FAIL
}
